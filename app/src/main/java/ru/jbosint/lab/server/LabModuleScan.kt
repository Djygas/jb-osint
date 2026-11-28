package ru.jbosint.lab.server

import ru.jbosint.osint.data.CustomModuleSpec
import ru.jbosint.osint.data.GitHubLabSpec

/**
 * read-only осмотр клонированного репозитория: ищем в документации и конфигах
 * HTTPS-адреса с маркером [CustomModuleSpec.PLACEHOLDER]. Ничего не устанавливаем
 * и не выполняем из репозитория — только читаем файлы через встроенную лабораторию.
 */
object LabModuleScan {

    const val MARKER = "JB_LAB_MODULE_URL="

    /**
     * Команда выполняется встроенным сервером в каталоге проекта. Ограничиваем размер
     * файлов и число проверяемых файлов, чтобы не читать дампы и не зависеть от
     * содержимого репозитория.
     */
    fun command(): String = """
        find . -type f \
          \( -iname 'readme*' -o -iname '*.md' -o -iname '*.json' \
             -o -iname '*.yaml' -o -iname '*.yml' -o -iname '*.txt' \
             -o -iname '*.py' -o -iname '*.js' -o -iname '*.ts' \) \
          -not -path '*/.git/*' -not -path '*/.jb_venv/*' -not -path '*/node_modules/*' \
          -size -512k 2>/dev/null | head -80 | while IFS= read -r f; do
          grep -hEo 'https://[^[:space:]"<>)]+' "${'$'}f" 2>/dev/null || true
        done | grep -F '${CustomModuleSpec.PLACEHOLDER}' | sort -u | head -12 | while IFS= read -r u; do
          printf '${MARKER}%s\n' "${'$'}u"
        done
    """.trimIndent()

    /** Нормализуем найденные строки тем же правилом, что и редактор модулей. */
    fun candidates(output: String): List<String> = output.lineSequence()
        .filter { it.startsWith(MARKER) }
        .mapNotNull { line ->
            GitHubLabSpec.moduleTemplates(line.removePrefix(MARKER).trim(), limit = 1).firstOrNull()
        }
        .distinct()
        .take(12)
        .toList()
}
