package ru.jbosint.lab.model

/** Тип проекта, определённый сервером по файлам репозитория. */
enum class LabProjectType(val wire: String, val title: String) {
    ANDROID("android", "Android / Gradle"),
    PYTHON("python", "Python"),
    NODE("node", "Node.js"),
    BASH("bash", "Shell"),
    OTHER("other", "Другой");

    companion object {
        fun from(value: String?): LabProjectType =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: OTHER
    }
}

/** Найденный исполняемый файл (относительный путь внутри проекта). */
data class LabScript(
    val name: String,
    val path: String,
    val type: LabProjectType,
    /** Заголовок кнопки из `# JB_BUTTON` (если размечен). */
    val button: String? = null,
    /** Имя параметра из `# JB_PARAM` (если скрипт принимает аргумент). */
    val param: String? = null
) {
    /** Команда запуска с уже подставленным путём (cwd на сервере = каталог проекта). */
    fun command(): String = when (type) {
        LabProjectType.PYTHON -> "python3 " + quote(path)
        LabProjectType.NODE -> "node " + quote(path)
        LabProjectType.BASH -> "bash " + quote(path)
        LabProjectType.ANDROID -> "./gradlew " + quote(path)
        LabProjectType.OTHER -> "bash " + quote(path)
    }

    private fun quote(value: String): String =
        if (value.any { it.isWhitespace() || it == '\'' }) "'" + value.replace("'", "'\\''") + "'"
        else value
}

data class LabProject(
    val url: String,
    val ref: String,
    val dir: String,
    val type: LabProjectType,
    val scripts: List<LabScript> = emptyList()
)

data class LabRunResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val tookMs: Long
)

data class LabLogLine(
    val at: Long = System.currentTimeMillis(),
    val text: String,
    val error: Boolean = false
)

/** Настройки клиента: адрес локального сервера и токен из setup_lab.sh. */
data class LabApiConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val token: String = ""
) {
    companion object {
        const val DEFAULT_BASE_URL = "http://127.0.0.1:8081"
        const val DEFAULT_TIMEOUT_SEC = 600
    }
}

/** Готовая команда-кнопка (динамические действия на экране). */
data class LabCommandTemplate(val title: String, val command: String)

class LabException(message: String) : Exception(message)
