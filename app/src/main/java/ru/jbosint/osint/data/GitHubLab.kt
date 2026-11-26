package ru.jbosint.osint.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/** Тип проекта в лаборатории: от него зависит подготовка окружения. */
enum class LabProjectType(val title: String) {
    ANDROID("Android / Gradle"),
    PYTHON("Python"),
    NODE("Node.js"),
    OTHER("Другой")
}

/** Параметры лаборатории. Зеркало файла ~/.config/jb_osint/github_lab.json. */
data class GitHubLabConfig(
    val workDir: String = DEFAULT_WORK_DIR,
    val timeoutSec: Int = 600,
    val allowedDomains: List<String> = DEFAULT_DOMAINS,
    val defaultShell: String = DEFAULT_SHELL,
    val venvPython: String = DEFAULT_VENV_PYTHON
) {
    fun toJson(): String = JSONObject()
        .put("work_dir", workDir)
        .put("timeout_sec", timeoutSec)
        .put("allowed_domains", JSONArray(allowedDomains))
        .put("default_shell", defaultShell)
        .put("venv_python", venvPython)
        .toString(2)

    companion object {
        val DEFAULT_WORK_DIR: String = "${TermuxSpec.DEFAULT_HOME}/.jb_osint/lab"
        val DEFAULT_SHELL: String = "${TermuxSpec.PREFIX}/bin/bash"
        val DEFAULT_VENV_PYTHON: String = "${TermuxSpec.PREFIX}/bin/python3"
        val DEFAULT_DOMAINS: List<String> = listOf("github.com", "raw.githubusercontent.com")

        fun decode(raw: String?): GitHubLabConfig {
            if (raw.isNullOrBlank()) return GitHubLabConfig()
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return GitHubLabConfig()
            val domains = root.optJSONArray("allowed_domains")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optString(index).trim().lowercase().takeIf { it.isNotBlank() }
                }
            }?.ifEmpty { null } ?: DEFAULT_DOMAINS
            return GitHubLabConfig(
                workDir = GitHubLabSpec.safeWorkDir(root.optString("work_dir")),
                timeoutSec = root.optInt("timeout_sec", 600).coerceIn(60, 3600),
                allowedDomains = domains,
                defaultShell = GitHubLabSpec.safeShell(root.optString("default_shell")),
                venvPython = GitHubLabSpec.safePython(root.optString("venv_python"))
            )
        }
    }
}

/** Проект, подготовленный в лаборатории. */
data class LabProject(
    val hash: String,
    val url: String,
    val ref: String,
    val dir: String,
    val type: LabProjectType = LabProjectType.OTHER
)

/**
 * Правила GitHub-лаборатории — чистые функции без Android.
 *
 * Безопасность: только github.com из whitelist, глубина 1 и одна ветка, каталог
 * с правами 700, таймаут на выполнение, запрет sudo/su/doas. Код репозитория
 * НЕ запускается при открытии: только после явного действия пользователя.
 */
object GitHubLabSpec {

    private const val HOME = TermuxSpec.DEFAULT_HOME
    const val CONFIG_FILE = HOME + "/.config/jb_osint/github_lab.json"
    const val MAX_COMMAND = 4_000

    private val safePath = Regex("^[A-Za-z0-9._/-]+$")
    private val safeRef = Regex("^[A-Za-z0-9._/-]{1,100}$")
    private val forbidden = Regex("(?i)(^|[\\s;&|()`\\$])(sudo|su|doas)\\b")

    fun safeWorkDir(raw: String): String {
        val value = raw.trim().trimEnd('/')
        if (!value.startsWith("$HOME/") || value.contains("..") || !safePath.matches(value)) {
            return GitHubLabConfig.DEFAULT_WORK_DIR
        }
        return value
    }

    fun safeShell(raw: String): String {
        val value = raw.trim()
        return if (value.startsWith("${TermuxSpec.PREFIX}/") && safePath.matches(value)) value
        else GitHubLabConfig.DEFAULT_SHELL
    }

    fun safePython(raw: String): String {
        val value = raw.trim()
        return if (value.startsWith("${TermuxSpec.PREFIX}/") && safePath.matches(value)) value
        else GitHubLabConfig.DEFAULT_VENV_PYTHON
    }

    /** Ветка/тег: без пробелов и ведущего дефиса, без `..`, только безопасные символы. */
    fun sanitizeRef(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        if (value.startsWith("-") || value.contains("..") || !safeRef.matches(value)) return null
        return value
    }

    fun hostAllowed(config: GitHubLabConfig, host: String): Boolean =
        config.allowedDomains.any { it.equals(host, ignoreCase = true) }

    /** Проверка URL и расчёт каталога проекта. null — ссылка/ветка недопустимы. */
    fun projectFor(config: GitHubLabConfig, url: String, ref: String): LabProject? {
        val repo = PluginRules.parseRepo(url) ?: return null
        if (!hostAllowed(config, "github.com")) return null
        val cleanRef = sanitizeRef(ref) ?: return null
        val normalized = "https://github.com/${repo.owner}/${repo.name}"
        val hash = hashFor(normalized, cleanRef)
        return LabProject(
            hash = hash,
            url = normalized,
            ref = cleanRef,
            dir = "${safeWorkDir(config.workDir)}/$hash",
            type = LabProjectType.OTHER
        )
    }

    fun hashFor(url: String, ref: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$url@$ref".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** true — команда содержит запрещённую эскалацию прав. */
    fun isCommandForbidden(command: String): Boolean = forbidden.containsMatchIn(command)

    fun detectType(files: Collection<String>): LabProjectType {
        val names = files.map { it.substringAfterLast('/').lowercase() }.toSet()
        return when {
            names.contains("build.gradle") || names.contains("build.gradle.kts") ||
                names.contains("settings.gradle") || names.contains("settings.gradle.kts") ->
                LabProjectType.ANDROID
            names.contains("requirements.txt") || names.contains("pyproject.toml") ->
                LabProjectType.PYTHON
            names.contains("package.json") -> LabProjectType.NODE
            else -> LabProjectType.OTHER
        }
    }

    fun typeFromMarker(value: String): LabProjectType = when (value.trim().lowercase()) {
        "android" -> LabProjectType.ANDROID
        "python" -> LabProjectType.PYTHON
        "node" -> LabProjectType.NODE
        else -> LabProjectType.OTHER
    }

    /** Скрипт настройки Termux (файл конфигурации + рабочая папка с правами 700). */
    fun setupScript(config: GitHubLabConfig = GitHubLabConfig()): String = buildString {
        append("#!/data/data/com.termux/files/usr/bin/bash\n")
        append("set -e\n")
        append("CONFIG_DIR=\"\$HOME/.config/jb_osint\"\n")
        append("WORK=\"\$HOME/.jb_osint/lab\"\n")
        append("mkdir -p \"\$CONFIG_DIR\" \"\$WORK\"\n")
        append("chmod 700 \"\$WORK\"\n")
        append("cat > \"\$CONFIG_DIR/github_lab.json\" <<'JBOSINT'\n")
        append(config.toJson()).append('\n')
        append("JBOSINT\n")
        append("chmod 600 \"\$CONFIG_DIR/github_lab.json\"\n")
        append("echo \"JB_LAB_SETUP_OK\"\n")
    }

    /** Клонирование и определение типа проекта. Код репозитория не запускается. */
    fun cloneScript(
        config: GitHubLabConfig,
        project: LabProject,
        verifyGpg: Boolean = false
    ): String {
        val branch = if (project.ref.isBlank()) "" else "--branch ${q(project.ref)}"
        val gpgCheck = if (verifyGpg) "1" else "0"
        return """
            set -euo pipefail
            umask 077
            WORK=${q(safeWorkDir(config.workDir))}
            PROJ=${q(project.dir)}
            mkdir -p "${'$'}WORK"
            chmod 700 "${'$'}WORK"
            rm -rf -- "${'$'}PROJ"
            mkdir -p "${'$'}PROJ"
            chmod 700 "${'$'}PROJ"
            echo "JB_LAB_STAGE=clone"
            git clone --depth 1 --single-branch $branch ${q(project.url)} "${'$'}PROJ"
            if [ -f "${'$'}PROJ/build.gradle" ] || [ -f "${'$'}PROJ/build.gradle.kts" ] ||
               [ -f "${'$'}PROJ/settings.gradle" ] || [ -f "${'$'}PROJ/settings.gradle.kts" ]; then
              TYPE=android
            elif [ -f "${'$'}PROJ/requirements.txt" ] || [ -f "${'$'}PROJ/pyproject.toml" ]; then
              TYPE=python
            elif [ -f "${'$'}PROJ/package.json" ]; then
              TYPE=node
            else
              TYPE=other
            fi
            echo "JB_LAB_TYPE=${'$'}TYPE"
            echo "JB_LAB_DIR=${'$'}PROJ"
            echo "JB_LAB_HASH=${project.hash}"
            if [ "${'$'}TYPE" = android ] && [ -f "${'$'}PROJ/gradlew" ]; then
              chmod +x "${'$'}PROJ/gradlew" || true
            fi
            if [ "${'$'}TYPE" = python ]; then
              ${q(safePython(config.venvPython))} -m venv "${'$'}PROJ/.jb_venv" >/dev/null 2>&1 || true
            fi
            if [ "$gpgCheck" = "1" ]; then
              if git -C "${'$'}PROJ" verify-commit HEAD >/dev/null 2>&1; then
                echo "JB_LAB_GPG=ok"
              else
                echo "JB_LAB_GPG=fail"
              fi
            fi
            echo "JB_LAB_CLONE_OK"
        """.trimIndent()
    }

    /** Явная установка зависимостей (выполняет сторонний код/сеть). */
    fun installScript(config: GitHubLabConfig, project: LabProject): String = """
        set -uo pipefail
        PROJ=${q(project.dir)}
        [ -d "${'$'}PROJ" ] || { echo "JB_LAB_ERROR=no_project"; exit 3; }
        cd "${'$'}PROJ" || exit 3
        FAIL=0
        if [ -f build.gradle ] || [ -f build.gradle.kts ] || [ -f settings.gradle ] || [ -f settings.gradle.kts ]; then
          [ -x ./gradlew ] || chmod +x ./gradlew 2>/dev/null || true
          ./gradlew --version 2>&1 | tee -a "${'$'}PROJ/.jb_osint_lab.log" || FAIL=1
        elif [ -f requirements.txt ]; then
          ${q(safePython(config.venvPython))} -m venv "${'$'}PROJ/.jb_venv" >/dev/null 2>&1 || true
          "${'$'}PROJ/.jb_venv/bin/pip" install --disable-pip-version-check -r requirements.txt 2>&1 | tee -a "${'$'}PROJ/.jb_osint_lab.log" || FAIL=1
        elif [ -f package.json ]; then
          if [ -f yarn.lock ] && command -v yarn >/dev/null 2>&1; then
            yarn install --ignore-scripts 2>&1 | tee -a "${'$'}PROJ/.jb_osint_lab.log" || FAIL=1
          else
            npm install --ignore-scripts 2>&1 | tee -a "${'$'}PROJ/.jb_osint_lab.log" || FAIL=1
          fi
        else
          echo "JB_LAB_NOTE=неизвестный тип проекта"
        fi
        if [ "${'$'}FAIL" = "0" ]; then
          echo "JB_LAB_INSTALL_OK"
        else
          echo "JB_LAB_INSTALL_FAIL"
          exit 1
        fi
    """.trimIndent()

    /** Запуск команды/скрипта только по явному действию, с таймаутом и логом. */
    fun runScript(config: GitHubLabConfig, project: LabProject, command: String): String {
        val encoded = Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_8))
        val timeout = config.timeoutSec.coerceIn(60, 3600)
        return """
            set -uo pipefail
            PROJ=${q(project.dir)}
            [ -d "${'$'}PROJ" ] || { echo "JB_LAB_ERROR=no_project"; exit 3; }
            cd "${'$'}PROJ" || exit 3
            CMD=${'$'}(printf %s ${q(encoded)} | base64 -d)
            case "${'$'}CMD" in
              *sudo*|*doas*|*" su "*|*"/su "*)
                echo "JB_LAB_BLOCKED=escalation"; exit 4;;
            esac
            echo "JB_LAB_CMD=${'$'}CMD"
            set -o pipefail
            timeout ${timeout} bash -lc "${'$'}CMD" 2>&1 | tee -a "${'$'}PROJ/.jb_osint_lab.log"
            EXIT=${'$'}?
            echo "JB_LAB_EXIT=${'$'}EXIT"
            exit "${'$'}EXIT"
        """.trimIndent()
    }

    fun clearScript(config: GitHubLabConfig, project: LabProject): String = """
        set -uo pipefail
        WORK=${q(safeWorkDir(config.workDir))}
        PROJ=${q(project.dir)}
        case "${'$'}PROJ" in
          "${'$'}WORK"/*) ;;
          *) echo "JB_LAB_ERROR=bad_path"; exit 2;;
        esac
        rm -rf -- "${'$'}PROJ"
        echo "JB_LAB_CLEARED"
    """.trimIndent()

    /** Экспорт лога и артефактов (APK/JAR) в Download/JB-Osint/reports. */
    fun exportScript(project: LabProject): String = """
        set -uo pipefail
        PROJ=${q(project.dir)}
        STORAGE="$HOME/storage/downloads"
        [ -d "${'$'}STORAGE" ] || { echo "JB_LAB_ERROR=no_storage"; echo "Выполните termux-setup-storage"; exit 5; }
        DEST="${'$'}STORAGE/JB-Osint/reports"
        OUT="${'$'}DEST/${project.hash}-${'$'}(date +%Y%m%d-%H%M%S)"
        mkdir -p "${'$'}OUT"
        cp "${'$'}PROJ/.jb_osint_lab.log" "${'$'}OUT/lab.log" 2>/dev/null || true
        find "${'$'}PROJ" -type f \( -name '*.apk' -o -name '*.jar' \) \
          -not -path '*/.gradle/*' -not -path '*/.git/*' 2>/dev/null | head -50 | while IFS= read -r f; do
          cp -- "${'$'}f" "${'$'}OUT/" 2>/dev/null || true
        done
        echo "JB_LAB_EXPORT=${'$'}OUT"
    """.trimIndent()

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
