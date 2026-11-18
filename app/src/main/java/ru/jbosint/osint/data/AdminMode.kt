package ru.jbosint.osint.data

import android.content.Context
import java.security.SecureRandom

/** Режим работы приложения: полный доступ или ограниченный «только поиск». */
enum class WorkMode(val id: String, val title: String, val note: String) {
    ADMIN(
        "admin",
        "Администратор",
        "Полный доступ: ключи, модули, настройки, резервные копии."
    ),
    USER(
        "user",
        "Пользователь",
        "Только поиск по настроенным модулям: ключи и настройки заблокированы."
    );

    companion object {
        fun of(id: String): WorkMode = entries.firstOrNull { it.id == id } ?: ADMIN
    }
}

/**
 * Правила мастер-пароля и блокировки. Вынесены из Android-хранилища,
 * чтобы их можно было проверить unit-тестами: длина, пауза после серии ошибок,
 * формат резервного кода.
 */
object AdminPolicy {

    const val MIN_LENGTH = VaultCrypto.MIN_PASSWORD
    const val MAX_FAILS = 5
    const val LOCK_MS = 5 * 60 * 1000L
    private const val RECOVERY_GROUPS = 4
    private const val RECOVERY_GROUP_LEN = 4
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** Текст ошибки для слишком простого пароля или null, если пароль подходит. */
    fun validate(password: String): String? = when {
        password.length < MIN_LENGTH -> "Пароль короче $MIN_LENGTH символов"
        password.length > 128 -> "Пароль длиннее 128 символов"
        password.trim().length != password.length -> "Пароль не должен начинаться или кончаться пробелом"
        password.all { it.isDigit() } -> "Пароль только из цифр слишком простой"
        else -> null
    }

    /** До какого времени действует блокировка после [fails] ошибок. */
    fun lockUntil(now: Long, fails: Int): Long = if (fails >= MAX_FAILS) now + LOCK_MS else 0L

    /** Понятный остаток блокировки: «4 мин 12 с». */
    fun lockText(msLeft: Long): String {
        if (msLeft <= 0) return ""
        val total = (msLeft + 999) / 1000
        val minutes = total / 60
        val seconds = total % 60
        return if (minutes > 0) "$minutes мин $seconds с" else "$seconds с"
    }

    fun newRecoveryCode(random: SecureRandom = SecureRandom()): String =
        (1..RECOVERY_GROUPS).joinToString("-") {
            (1..RECOVERY_GROUP_LEN).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        }

    /** Сравнение кодов без учёта регистра, пробелов и дефисов. */
    fun normaliseRecovery(code: String): String =
        code.uppercase().filter { it.isLetterOrDigit() }
}

/**
 * Состояние режима работы и мастер-пароля. Сам пароль не хранится:
 * в prefs лежит только PBKDF2-хеш ([PinHash]) и служебные счётчики.
 */
class AdminStore(context: Context) {

    private val prefs = context.getSharedPreferences("jb_admin", Context.MODE_PRIVATE)

    var mode: WorkMode
        get() = WorkMode.of(prefs.getString("mode", WorkMode.ADMIN.id).orEmpty())
        set(value) = prefs.edit().putString("mode", value.id).apply()

    fun hasPassword(): Boolean = prefs.getString("hash", "").orEmpty().isNotBlank()

    fun passwordSetAt(): Long = prefs.getLong("hash_at", 0L)

    fun setPassword(password: String): Boolean {
        if (AdminPolicy.validate(password) != null) return false
        prefs.edit()
            .putString("hash", PinHash.hash(password))
            .putLong("hash_at", System.currentTimeMillis())
            .putInt("fails", 0)
            .putLong("lock_until", 0L)
            .apply()
        return true
    }

    fun changePassword(old: String, new: String): Boolean {
        if (!verify(old)) return false
        return setPassword(new)
    }

    fun fails(): Int = prefs.getInt("fails", 0)

    fun lockedUntil(): Long = prefs.getLong("lock_until", 0L)

    fun lockLeft(now: Long = System.currentTimeMillis()): Long =
        (lockedUntil() - now).coerceAtLeast(0L)

    /**
     * Проверка пароля с учётом блокировки: пять ошибок подряд включают паузу
     * на пять минут, успешный ввод счётчик сбрасывает.
     */
    fun verify(password: String, now: Long = System.currentTimeMillis()): Boolean {
        if (hasPassword().not()) return false
        if (lockLeft(now) > 0) return false
        val stored = prefs.getString("hash", "").orEmpty()
        if (PinHash.verify(password, stored)) {
            prefs.edit().putInt("fails", 0).putLong("lock_until", 0L).apply()
            if (PinHash.needsRehash(stored)) prefs.edit().putString("hash", PinHash.hash(password)).apply()
            return true
        }
        val fails = fails() + 1
        val until = AdminPolicy.lockUntil(now, fails)
        prefs.edit()
            .putInt("fails", fails)
            .putLong("lock_until", until)
            .apply()
        return false
    }

    /** Создаёт резервный код; возвращает его в открытом виде ровно один раз. */
    fun generateRecoveryCode(): String {
        val code = AdminPolicy.newRecoveryCode()
        prefs.edit()
            .putString("recovery", PinHash.hash(AdminPolicy.normaliseRecovery(code)))
            .putLong("recovery_at", System.currentTimeMillis())
            .apply()
        return code
    }

    fun hasRecovery(): Boolean = prefs.getString("recovery", "").orEmpty().isNotBlank()

    /** Сброс пароля резервным кодом: старый пароль при этом перестаёт действовать. */
    fun resetWithRecovery(code: String, newPassword: String): Boolean {
        val stored = prefs.getString("recovery", "").orEmpty()
        if (stored.isBlank()) return false
        if (!PinHash.verify(AdminPolicy.normaliseRecovery(code), stored)) return false
        if (!setPassword(newPassword)) return false
        prefs.edit().remove("recovery").apply()
        return true
    }

    /** Биометрия разрешена пользователем и настроена на устройстве. */
    var biometric: Boolean
        get() = prefs.getBoolean("biometric", false)
        set(value) = prefs.edit().putBoolean("biometric", value).apply()

    /** Полный сброс режима: пароль, резервный код, блокировка. */
    fun clear() = prefs.edit().clear().apply()
}
