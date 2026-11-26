package ru.jbosint.lab.server

import java.util.Locale

/** Разобранная ссылка на репозиторий GitHub. */
data class LabRepo(val owner: String, val name: String, val ref: String)

/** Метаданные кнопки из комментариев скрипта (`# JB_BUTTON`, `# JB_PARAM`). */
data class LabButtonMeta(val title: String?, val param: String?)

/**
 * Чистые правила встроенной лаборатории: разбор ссылки, команд и метаданных.
 * Без Android — покрываются unit-тестами.
 */
object LabRules {

    private val ownerPattern = Regex("^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,38})$")
    private val repoPattern = Regex("^[A-Za-z0-9._-]{1,100}$")
    private val refPattern = Regex("^[A-Za-z0-9._/-]{1,100}$")

    private val allowedBin = setOf(
        "sh", "bash", "python", "python3", "node",
        "ls", "cat", "head", "tail", "grep", "find", "wc", "echo", "sed", "awk", "git"
    )
    private val blockedBin = setOf(
        "sudo", "su", "doas", "rm", "rmdir", "shutdown", "reboot", "pkg", "apt", "apt-get"
    )

    fun parseRepo(raw: String, refInput: String = ""): LabRepo? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        text = when {
            text.startsWith("http://", ignoreCase = true) -> "https://" + text.substring(7)
            text.startsWith("https://", ignoreCase = true) -> text
            else -> "https://$text"
        }
        text = text.substringBefore('?').substringBefore('#')
        val uri = runCatching { java.net.URI(text) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host?.lowercase(Locale.US) ?: return null
        if (host != "github.com" && host != "www.github.com") return null
        val parts = uri.path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val owner = parts[0]
        val name = parts[1].removeSuffix(".git")
        if (!ownerPattern.matches(owner) || !repoPattern.matches(name)) return null
        val fromUrl = when {
            parts.size >= 4 && parts[2] == "tree" -> parts.drop(3).joinToString("/")
            parts.size >= 5 && parts[2] == "releases" && parts[3] == "tag" -> parts.drop(4).joinToString("/")
            else -> ""
        }
        val explicit = refInput.trim()
        if (explicit.isNotEmpty()) {
            val clean = sanitizeRef(explicit)
            if (clean.isEmpty()) return null
            return LabRepo(owner, name, clean)
        }
        return LabRepo(owner, name, sanitizeRef(fromUrl))
    }

    fun sanitizeRef(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        if (value.startsWith("-") || value.contains("..") || !refPattern.matches(value)) return ""
        return value
    }

    /** Разбор команды на аргументы с поддержкой кавычек. Без shell-интерпретации. */
    fun splitArgs(command: String): List<String> {
        val out = ArrayList<String>()
        val buffer = StringBuilder()
        var quote = '\u0000'
        var index = 0
        while (index < command.length) {
            val ch = command[index]
            when {
                quote != '\u0000' -> {
                    if (ch == '\\' && quote == '"' && index + 1 < command.length) {
                        buffer.append(command[index + 1]); index++
                    } else if (ch == quote) {
                        quote = '\u0000'
                    } else {
                        buffer.append(ch)
                    }
                }
                ch == '\'' || ch == '"' -> quote = ch
                ch.isWhitespace() -> {
                    if (buffer.isNotEmpty()) {
                        out += buffer.toString(); buffer.clear()
                    }
                }
                else -> buffer.append(ch)
            }
            index++
        }
        if (buffer.isNotEmpty()) out += buffer.toString()
        return out
    }

    fun isBlocked(first: String): Boolean =
        first.substringAfterLast('/').lowercase(Locale.US) in blockedBin

    fun isAllowed(first: String, script: Boolean = false): Boolean {
        if (script) return true
        return first.substringAfterLast('/').lowercase(Locale.US) in allowedBin
    }

    /** Метаданные кнопки из первых строк скрипта. */
    fun buttonMeta(lines: List<String>): LabButtonMeta {
        var title: String? = null
        var param: String? = null
        for (line in lines) {
            val text = line.trim()
            if (!text.startsWith("#")) continue
            val upper = text.uppercase(Locale.US)
            if (upper.startsWith("# JB_BUTTON")) {
                val value = text.removePrefix("# JB_BUTTON").trimStart(':', ' ', '\t').trim()
                if (value.isNotEmpty()) title = value.take(80)
            } else if (upper.startsWith("# JB_PARAM")) {
                val value = text.removePrefix("# JB_PARAM").trimStart(':', ' ', '\t').trim()
                if (value.isNotEmpty()) param = value.take(60)
            }
        }
        return LabButtonMeta(title, param)
    }

    fun projectType(files: Set<String>): String {
        val names = files.map { it.substringAfterLast('/').lowercase(Locale.US) }.toSet()
        return when {
            names.any { it in setOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts") } -> "android"
            names.any { it in setOf("requirements.txt", "pyproject.toml") } -> "python"
            "package.json" in names -> "node"
            else -> "other"
        }
    }

    fun scriptType(path: String): String? = when (path.substringAfterLast('.', "").lowercase(Locale.US)) {
        "sh" -> "bash"
        "py" -> "python"
        else -> null
    }
}
