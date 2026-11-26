package ru.jbosint.osint.data.module

import android.content.Context
import ru.jbosint.osint.data.CustomAuth
import ru.jbosint.osint.data.CustomModuleChecker
import ru.jbosint.osint.data.CustomModuleStore
import ru.jbosint.osint.data.KeyCheck
import ru.jbosint.osint.data.KeyRepository
import ru.jbosint.osint.data.KeyState
import ru.jbosint.osint.data.KeyValidator
import ru.jbosint.osint.data.ProviderCatalog
import java.io.File

/** Встроенный модуль (каталог) или модуль, добавленный пользователем. */
enum class ModuleKind(val title: String) {
    BUILT_IN("Встроенный"),
    USER_CUSTOM("Свой модуль")
}

/** Итог проверки модуля в мастере. */
enum class ModuleStatus(val title: String) {
    ACTIVE("Работает"),
    NEEDS_KEY("Нужен ключ"),
    NO_NETWORK("Нет сети"),
    FAILED("Ошибка"),
    UNKNOWN("Не проверен");

    companion object {
        fun fromKeyState(state: KeyState): ModuleStatus = when (state) {
            KeyState.VALID -> ACTIVE
            KeyState.EMPTY -> NEEDS_KEY
            KeyState.OFFLINE -> NO_NETWORK
            KeyState.INVALID -> FAILED
            KeyState.UNCHECKED, KeyState.CHECKING -> UNKNOWN
        }
    }
}

/** Фильтр списка модулей на первом шаге мастера. */
enum class WizardFilter(val title: String) {
    ALL("Все"),
    WORKING("Работают"),
    NEEDS_KEY("Нужен ключ"),
    NO_NETWORK("Нет сети");

    fun matches(info: ModuleInfo, status: ModuleStatus = ModuleStatus.UNKNOWN): Boolean {
        val current = if (status == ModuleStatus.UNKNOWN) info.status else status
        return when (this) {
            ALL -> true
            WORKING -> current == ModuleStatus.ACTIVE
            NEEDS_KEY -> current == ModuleStatus.NEEDS_KEY || info.needsKey
            NO_NETWORK -> current == ModuleStatus.NO_NETWORK
        }
    }
}

/** Описание модуля для мастера настройки и управления. */
data class ModuleInfo(
    val id: String,
    val name: String,
    val kind: ModuleKind,
    val isActive: Boolean,
    val isHidden: Boolean,
    val hasKey: Boolean,
    val keyless: Boolean,
    val status: ModuleStatus,
    val docsUrl: String = "",
    val description: String = "",
    val hint: String = "",
    val requiresSecondField: String? = null,
    val secondFieldFilled: Boolean = false
) {
    val needsKey: Boolean get() = !keyless && !hasKey
}

/**
 * Хранилище модулей поверх существующих KeyRepository/CustomModuleStore.
 * Отдельной Room-таблицы нет: ключи и модули уже живут в зашифрованном хранилище,
 * а мастер работает с ними, не создавая второй источник правды.
 */
interface ModuleRepository {
    fun all(includeHidden: Boolean): List<ModuleInfo>
    fun get(id: String): ModuleInfo?
    fun isBuiltIn(id: String): Boolean
    fun isHidden(id: String): Boolean
    fun hasKey(id: String): Boolean
    fun secondField(id: String): String
    suspend fun checkKey(id: String): KeyCheck
    fun saveApiKey(id: String, value: String)
    fun saveSecondField(id: String, value: String)
    fun setActive(id: String, active: Boolean)
    fun setHidden(id: String, hidden: Boolean)
    fun delete(id: String)
    fun deleteCache(id: String)
}

class AppModuleRepository(
    private val context: Context,
    private val keys: KeyRepository,
    private val custom: CustomModuleStore,
    private val validator: KeyValidator,
    private val customChecker: CustomModuleChecker
) : ModuleRepository {

    override fun all(includeHidden: Boolean): List<ModuleInfo> {
        val builtIn = ProviderCatalog.all.map { spec ->
            ModuleInfo(
                id = spec.id,
                name = spec.title,
                kind = ModuleKind.BUILT_IN,
                isActive = keys.isEnabled(spec.id),
                isHidden = keys.isHidden(spec.id),
                hasKey = spec.keyless || keys.key(spec.id).isNotBlank(),
                keyless = spec.keyless,
                status = ModuleStatus.fromKeyState(keys.state(spec.id)),
                docsUrl = spec.docsUrl,
                description = spec.subtitle,
                hint = spec.hint,
                requiresSecondField = spec.requiresSecondField,
                secondFieldFilled = keys.secondField(spec.id).isNotBlank()
            )
        }
        val user = custom.list().map { module ->
            ModuleInfo(
                id = module.id,
                name = module.title,
                kind = ModuleKind.USER_CUSTOM,
                isActive = module.enabled,
                isHidden = module.hidden,
                hasKey = module.auth == CustomAuth.NONE || custom.key(module.id).isNotBlank(),
                keyless = module.auth == CustomAuth.NONE,
                status = ModuleStatus.UNKNOWN,
                docsUrl = "",
                description = "Свой модуль · ${module.method}",
                hint = module.auth.hint,
                requiresSecondField = null,
                secondFieldFilled = false
            )
        }
        return (builtIn + user).filter { includeHidden || !it.isHidden }
    }

    override fun get(id: String): ModuleInfo? = all(includeHidden = true).firstOrNull { it.id == id }

    override fun isBuiltIn(id: String): Boolean = ProviderCatalog.byId(id) != null

    override fun isHidden(id: String): Boolean =
        if (isBuiltIn(id)) keys.isHidden(id) else custom.get(id)?.hidden == true

    override fun hasKey(id: String): Boolean =
        ProviderCatalog.byId(id)?.let { spec -> spec.keyless || keys.key(id).isNotBlank() }
            ?: (custom.get(id)?.let { it.auth == CustomAuth.NONE || custom.key(id).isNotBlank() } ?: false)

    override fun secondField(id: String): String = keys.secondField(id)

    override suspend fun checkKey(id: String): KeyCheck {
        if (isBuiltIn(id)) return validator.check(id)
        val module = custom.get(id) ?: return KeyCheck(KeyState.INVALID, "Модуль не найден")
        return customChecker.check(module)
    }

    override fun saveApiKey(id: String, value: String) {
        if (isBuiltIn(id)) {
            keys.saveKey(id, value)
        } else {
            val module = custom.get(id) ?: return
            custom.save(module, value)
        }
    }

    override fun saveSecondField(id: String, value: String) {
        if (isBuiltIn(id)) keys.saveSecondField(id, value)
    }

    override fun setActive(id: String, active: Boolean) {
        if (isBuiltIn(id)) keys.setEnabled(id, active) else custom.setEnabled(id, active)
    }

    override fun setHidden(id: String, hidden: Boolean) {
        if (isBuiltIn(id)) keys.setHidden(id, hidden) else custom.setHidden(id, hidden)
    }

    /**
     * Удаление: пользовательский модуль стирается вместе с ключом, встроенный —
     * только скрывается (код модуля остаётся). Кэш модуля удаляется в обоих случаях.
     */
    override fun delete(id: String) {
        if (isBuiltIn(id)) keys.setHidden(id, true) else custom.delete(id)
        deleteCache(id)
    }

    override fun deleteCache(id: String) {
        runCatching { File(context.cacheDir, "modules/$id").deleteRecursively() }
    }
}
