package ru.jbosint.osint.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Одна строка диагностики локального бэкенда. */
data class TermuxCheck(
    val id: String,
    val title: String,
    val ok: Boolean,
    val detail: String,
    val fix: String = ""
)

/** Результат выполнения команды в Termux, пришедший через PendingIntent. */
data class TermuxResult(
    val requestId: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val error: String = ""
) {
    val ok: Boolean get() = exitCode == 0 && error.isBlank()
}

/**
 * Спецификация Termux-моста: только официальный RUN_COMMAND, только доверенные
 * скрипты внутри домашней папки Termux. Никакого произвольного кода из UI.
 */
object TermuxSpec {

    const val PACKAGE = "com.termux"
    const val SERVICE = "com.termux.app.RunCommandService"
    const val ACTION = "com.termux.RUN_COMMAND"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"

    const val DEFAULT_HOME = "/data/data/com.termux/files/home"
    const val PREFIX = "/data/data/com.termux/files/usr"
    const val BASH = "$PREFIX/bin/bash"

    const val SCRIPTS_DIRNAME = "JB_OSINT_Scripts"
    const val SHARED_DIR = "/storage/emulated/0/JB_OSINT_Crawl4AI"
    const val RAE_OUTPUT = "api_client.py"

    // Extras из TermuxConstants (termux-shared). Значения стабильны с 0.109+.
    const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
    const val RESULT_BUNDLE = "result"

    val DEFAULT_TRUSTED = listOf("rae_manual.sh")

    /** Имя скрипта: буквы/цифры в начале, дальше точка, дефис, подчёркивание; .py или .sh. */
    fun sanitizeScriptName(raw: String): String? {
        val name = raw.trim()
        if (name.length !in 1..64) return null
        if (name.startsWith(".")) return null
        if (!name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) return null
        if (name.contains("..")) return null
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext != "py" && ext != "sh") return null
        return name
    }

    /** Домашняя папка Termux: только /data/data/com.termux/files/home[/подпапки]. */
    fun normalizeHome(raw: String): String {
        val path = raw.trim().trimEnd('/')
        if (path.isEmpty()) return DEFAULT_HOME
        if (path != DEFAULT_HOME && !path.startsWith("$DEFAULT_HOME/")) return DEFAULT_HOME
        val tail = path.removePrefix(DEFAULT_HOME).trimStart('/')
        if (tail.isEmpty()) return DEFAULT_HOME
        val segments = tail.split('/')
        val safe = segments.all {
            it.isNotEmpty() && it != "." && it != ".." && it.matches(Regex("[A-Za-z0-9._-]+"))
        }
        return if (safe) "$DEFAULT_HOME/$tail" else DEFAULT_HOME
    }

    fun scriptsDir(home: String): String = "${normalizeHome(home)}/$SCRIPTS_DIRNAME"

    /** Абсолютный путь к доверенному скрипту или null, если имя/путь не проходят проверку. */
    fun scriptPath(home: String, rawName: String): String? {
        val name = sanitizeScriptName(rawName) ?: return null
        val raw = home.trim().trimEnd('/')
        val normalized = normalizeHome(raw)
        if (raw.isNotEmpty() && raw != normalized) return null
        return "${normalized}/$SCRIPTS_DIRNAME/$name"
    }

    fun isTrusted(trusted: List<String>, rawName: String): Boolean {
        val name = sanitizeScriptName(rawName) ?: return false
        return trusted.any { it == name }
    }

    /** Команда самодиагностики: печатает маркеры, которые разбирает приложение. */
    fun diagnosticsCommand(): String = listOf(
        "echo JB_TERMUX_OK",
        "if command -v python >/dev/null 2>&1; then printf 'JB_PYTHON=%s\\n' \"\$(python --version 2>&1)\"; else echo JB_NO_PYTHON; fi",
        "if [ -d \"\$HOME/storage/shared\" ]; then echo JB_STORAGE_OK; else echo JB_NO_STORAGE; fi",
        "if [ -d \"\$HOME/$SCRIPTS_DIRNAME\" ]; then echo JB_SCRIPTS_OK; else echo JB_NO_SCRIPTS; fi",
        "if [ -d \"\$HOME/storage/shared/JB_OSINT_Crawl4AI\" ]; then echo JB_SHARED_OK; else echo JB_NO_SHARED; fi",
        "if command -v crawl4ai >/dev/null 2>&1; then echo JB_CRAWL4AI_OK; else echo JB_NO_CRAWL4AI; fi",
        "if command -v markitdown >/dev/null 2>&1; then echo JB_MARKITDOWN_OK; else echo JB_NO_MARKITDOWN; fi",
        "if command -v reverse-api-engineer >/dev/null 2>&1; then echo JB_RAE_OK; else echo JB_NO_RAE; fi",
        "if command -v node >/dev/null 2>&1; then printf 'JB_NODE=%s\\n' \"\$(node --version 2>&1)\"; else echo JB_NO_NODE; fi"
    ).joinToString("\n")

    fun parseDiagnostics(stdout: String): List<TermuxCheck> {
        val text = stdout.replace("\r", "")
        fun marker(name: String) = Regex("(?m)^${Regex.escape(name)}\\s*$").containsMatchIn(text)
        fun value(prefix: String) = text.lineSequence()
            .firstOrNull { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val python = value("JB_PYTHON=")
        val node = value("JB_NODE=")
        return listOf(
            TermuxCheck(
                id = "python",
                title = "Python",
                ok = python != null,
                detail = python ?: "Не найден",
                fix = "pkg install -y python"
            ),
            TermuxCheck(
                id = "storage",
                title = "Общая папка (Storage)",
                ok = marker("JB_STORAGE_OK"),
                detail = if (marker("JB_STORAGE_OK")) "~/storage/shared доступна"
                else "Нет доступа: выполните termux-setup-storage",
                fix = "termux-setup-storage"
            ),
            TermuxCheck(
                id = "scripts",
                title = "Папка скриптов",
                ok = marker("JB_SCRIPTS_OK"),
                detail = if (marker("JB_SCRIPTS_OK")) "~/JB_OSINT_Scripts создана"
                else "Создастся скриптом настройки",
                fix = "mkdir -p ~/JB_OSINT_Scripts"
            ),
            TermuxCheck(
                id = "shared",
                title = "Папка обмена",
                ok = marker("JB_SHARED_OK"),
                detail = if (marker("JB_SHARED_OK")) SHARED_DIR
                else "Создастся скриптом настройки",
                fix = "mkdir -p ~/storage/shared/JB_OSINT_Crawl4AI"
            ),
            TermuxCheck(
                id = "crawl4ai",
                title = "Crawl4AI",
                ok = marker("JB_CRAWL4AI_OK"),
                detail = if (marker("JB_CRAWL4AI_OK")) "Установлен" else "Не установлен",
                fix = "pip install -e \".[all]\" в ~/crawl4ai"
            ),
            TermuxCheck(
                id = "markitdown",
                title = "MarkItDown",
                ok = marker("JB_MARKITDOWN_OK"),
                detail = if (marker("JB_MARKITDOWN_OK")) "Установлен" else "Не установлен",
                fix = "pip install 'markitdown[all]'"
            ),
            TermuxCheck(
                id = "rae",
                title = "Reverse API Engineer",
                ok = marker("JB_RAE_OK"),
                detail = if (marker("JB_RAE_OK")) "Установлен" else "Не установлен",
                fix = "pip install 'reverse-api-engineer[manual]'"
            ),
            TermuxCheck(
                id = "node",
                title = "Node.js",
                ok = node != null,
                detail = node ?: "Не найден (нужен Crawl4AI)",
                fix = "pkg install -y nodejs"
            )
        )
    }

    /** Локальные проверки без запуска Termux: пакет и разрешение. */
    fun localChecks(context: Context): List<TermuxCheck> {
        val installed = runCatching {
            context.packageManager.getPackageInfo(PACKAGE, 0)
        }.isSuccess
        val granted = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
        return listOf(
            TermuxCheck(
                id = "termux",
                title = "Termux установлен",
                ok = installed,
                detail = if (installed) "Пакет com.termux найден"
                else "Установите Termux из F-Droid",
                fix = "https://f-droid.org/packages/com.termux/"
            ),
            TermuxCheck(
                id = "permission",
                title = "Разрешение RUN_COMMAND",
                ok = granted,
                detail = if (granted) "Разрешено"
                else "Выдайте разрешение кнопкой «Разрешить»",
                fix = "Разрешение com.termux.permission.RUN_COMMAND"
            )
        )
    }

    /**
     * Скрипт авто-настройки Termux из ТЗ + мост RAE. Идемпотентен: повторный запуск
     * не клонирует репозиторий заново и не дублирует allow-external-apps.
     */
    fun setupScript(home: String = DEFAULT_HOME): String {
        val dir = scriptsDir(home)
        val lines = mutableListOf(
            "#!/data/data/com.termux/files/usr/bin/bash",
            "set -e",
            "echo '[JB OSINT] Обновление пакетов'",
            "pkg update -y && pkg upgrade -y",
            "echo '[JB OSINT] Зависимости'",
            "pkg install -y python git nodejs build-essential libffi-dev nss ca-certificates",
            "echo '[JB OSINT] Доступ к общей папке'",
            "termux-setup-storage || true",
            "mkdir -p ~/storage/shared/JB_OSINT_Crawl4AI",
            "mkdir -p \"$dir\"",
            "mkdir -p \"\$HOME/.termux\"",
            "grep -q '^allow-external-apps=true' \"\$HOME/.termux/termux.properties\" 2>/dev/null || echo 'allow-external-apps=true' >> \"\$HOME/.termux/termux.properties\"",
            "echo '[JB OSINT] Crawl4AI'",
            "if [ ! -d \"\$HOME/crawl4ai\" ]; then git clone https://github.com/unclecode/crawl4ai.git \"\$HOME/crawl4ai\"; fi",
            "cd \"\$HOME/crawl4ai\"",
            "pip install -e \".[all]\"",
            "crawl4ai-setup",
            "echo '[JB OSINT] MarkItDown'",
            "pip install 'markitdown[all]'",
            "echo '[JB OSINT] Reverse API Engineer'",
            "pip install 'reverse-api-engineer[manual]'",
            "playwright install chromium",
            "echo '[JB OSINT] Мост RAE'",
            "cat > \"$dir/rae_manual.sh\" <<'EOF'",
            "#!/data/data/com.termux/files/usr/bin/bash",
            "# Ручной захват трафика: откроется браузер. Пройдите по сайту и закройте браузер.",
            "cd \"$dir\"",
            "reverse-api-engineer",
            "LATEST=\$(find \"$dir/scripts\" -name api_client.py -printf '%T@ %p\\n' 2>/dev/null | sort -n | tail -1 | cut -d' ' -f2-)",
            "if [ -n \"\$LATEST\" ]; then",
            "  cp \"\$LATEST\" \"$SHARED_DIR/api_client.py\"",
            "  echo \"Клиент скопирован: $SHARED_DIR/api_client.py\"",
            "fi"
        )
        lines += "EOF"
        lines += "chmod +x \"$dir/rae_manual.sh\""
        lines += "echo '[JB OSINT] Готово. Скрипты: $dir'"
        return lines.joinToString("\n")
    }

    /** Команда проверки Python (для кнопки «Проверить Python»). */
    fun pythonCheckCommand(): String = listOf(
        "if command -v python >/dev/null 2>&1; then printf 'JB_PYTHON=%s\\n' \"\$(python --version 2>&1)\"; else echo JB_NO_PYTHON; fi"
    ).joinToString("\n")

    fun isPermissionGranted(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun buildPendingIntent(context: Context, requestId: String): PendingIntent {
        val intent = Intent(context, TermuxResultReceiver::class.java).apply {
            action = TermuxResultReceiver.ACTION
            putExtra(TermuxResultReceiver.EXTRA_REQUEST_ID, requestId)
        }
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            requestId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutable
        )
    }

    /** Запуск bash с фиксированной командой (диагностика). */
    fun startDiagnostics(context: Context, home: String, requestId: String): Result<Unit> =
        start(context, BASH, arrayOf("-lc", diagnosticsCommand()), normalizeHome(home), true, requestId)

    /** Запуск доверенного скрипта из белого списка. */
    fun startScript(
        context: Context,
        home: String,
        name: String,
        args: Array<String> = emptyArray(),
        background: Boolean = false,
        requestId: String
    ): Result<Unit> {
        val path = scriptPath(home, name)
            ?: return Result.failure(IllegalArgumentException("Недопустимое имя скрипта"))
        if (!path.startsWith("${normalizeHome(home)}/")) {
            return Result.failure(IllegalArgumentException("Скрипт вне домашней папки Termux"))
        }
        return start(context, path, args, normalizeHome(home), background, requestId)
    }

    private fun start(
        context: Context,
        path: String,
        args: Array<String>,
        home: String,
        background: Boolean,
        requestId: String
    ): Result<Unit> = runCatching {
        if (!isPermissionGranted(context)) {
            throw IllegalStateException("Нет разрешения com.termux.permission.RUN_COMMAND")
        }
        val intent = Intent().apply {
            setClassName(PACKAGE, SERVICE)
            action = ACTION
            putExtra(EXTRA_COMMAND_PATH, path)
            putExtra(EXTRA_ARGUMENTS, args)
            putExtra(EXTRA_WORKDIR, home)
            putExtra(EXTRA_BACKGROUND, background)
            putExtra(EXTRA_SESSION_ACTION, "0")
            putExtra(EXTRA_COMMAND_LABEL, "JB OSINT")
            putExtra(EXTRA_PENDING_INTENT, buildPendingIntent(context, requestId))
        }
        context.startService(intent)
        Unit
    }
}
