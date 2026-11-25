package ru.jbosint.osint.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** Что модуль принимает на вход. */
enum class PluginInput(val title: String) {
    USERNAME("Никнейм"),
    EMAIL("Почта"),
    DOMAIN("Домен"),
    PHONE("Телефон"),
    ANY("Любой запрос")
}

/** Как читать вывод скрипта. */
enum class PluginParser(val title: String) {
    SHERLOCK("Найденные профили"),
    EMAILS("Адреса почты"),
    HOSTS("Хосты и поддомены"),
    PLAIN("Строки результата")
}

/**
 * Правило авто-настройки: имя файла → тип входа, команда и разбор ответа.
 * Шаблоны не запускают код — они только заполняют карточку модуля.
 */
data class PluginTemplate(
    val pattern: String,
    val input: PluginInput,
    val command: String,
    val parser: PluginParser,
    val pretty: String
)

data class PluginModule(
    val id: String,
    val title: String,
    val file: String,
    val input: PluginInput,
    val command: String,
    val parser: PluginParser,
    val enabled: Boolean = false,
    /** false — шаблон не нашёлся, команду надо проверить вручную. */
    val known: Boolean = true
)

data class InstalledPlugin(
    val slug: String,
    val title: String,
    val source: String,
    val installedAt: Long,
    val modules: List<PluginModule>
)

/** Один результат, вытащенный из вывода скрипта. */
data class PluginHit(val kind: String, val value: String)

/**
 * Чистые правила маркетплейса: разбор ссылки, безопасные пути, шаблоны,
 * разбор вывода. Всё без Android и без сети — проверяется юнит-тестами.
 */
object PluginRules {

    data class Repo(val owner: String, val name: String) {
        val slug: String get() = "$owner-$name".lowercase()
        val page: String get() = "https://github.com/$owner/$name"
    }

    private val ownerName = Regex("^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,38})$")
    private val repoName = Regex("^[A-Za-z0-9._-]{1,100}$")
    private val scriptExt = setOf("py", "sh", "js")

    const val MAX_FILES = 2000
    const val MAX_FILE_BYTES = 1_000_000L
    const val MAX_TOTAL_BYTES = 40_000_000L
    const val MAX_DEPTH = 3

    /** Только github.com/owner/repo(.git); SSH, гитлабы и «просто строка» отклоняются. */
    fun parseRepo(raw: String): Repo? {
        val text = raw.trim()
        if (!text.startsWith("https://", ignoreCase = true)) return null
        val rest = text.removePrefix("https://").removePrefix("http://")
        val host = rest.substringBefore('/').lowercase()
        if (host != "github.com" && host != "www.github.com") return null
        val parts = rest.substringAfter('/').trim('/').removeSuffix(".git").split('/')
        if (parts.size != 2) return null
        val owner = parts[0]
        val name = parts[1]
        if (!ownerName.matches(owner) || !repoName.matches(name)) return null
        if (name == "." || name == "..") return null
        return Repo(owner, name)
    }

    /** Прямая ссылка на архив: без редиректов на чужие хосты. */
    fun zipUrl(repo: Repo): String = "https://codeload.github.com/${repo.owner}/${repo.name}/zip/HEAD"

    fun isScript(fileName: String): Boolean {
        val lower = fileName.lowercase()
        val ext = lower.substringAfterLast('.', "")
        if (ext !in scriptExt) return false
        if (lower.startsWith("test_") || lower.endsWith("_test.py") || lower.contains("/tests/")) return false
        return true
    }

    /**
     * Путь внутри папки плагина. Отсекает абсолютные пути, `..`, обратные слэши,
     * спецсимволы и слишком глубокую вложенность (zip-slip).
     */
    fun safeRelative(raw: String, maxDepth: Int = MAX_DEPTH): String? {
        val name = raw.trim().replace('\\', '/')
        if (name.isEmpty() || name.length > 200) return null
        if (name.startsWith("/") || name.contains("..")) return null
        if (name.any { it.code < 32 }) return null
        val parts = name.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > maxDepth) return null
        val ok = parts.all { part -> part.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")) }
        return if (ok) parts.joinToString("/") else null
    }

    private val templates = listOf(
        PluginTemplate("^sherlock(\\.py)?$", PluginInput.USERNAME, "sherlock {query}", PluginParser.SHERLOCK, "Sherlock"),
        PluginTemplate("^theharvester(\\.py|\\.sh)?$", PluginInput.DOMAIN, "theHarvester -d {query} -b duckduckgo", PluginParser.EMAILS, "theHarvester"),
        PluginTemplate("^sublist3r(\\.py)?$", PluginInput.DOMAIN, "python3 {file} -d {query}", PluginParser.HOSTS, "Sublist3r"),
        PluginTemplate("^holehe(\\.py)?$", PluginInput.EMAIL, "python3 {file} {query}", PluginParser.EMAILS, "Holehe"),
        PluginTemplate("^maigret(\\.py)?$", PluginInput.USERNAME, "python3 {file} {query}", PluginParser.SHERLOCK, "Maigret"),
        PluginTemplate("^phoneinfoga(\\.py)?$", PluginInput.PHONE, "python3 {file} scan -n {query}", PluginParser.PLAIN, "PhoneInfoga"),
        PluginTemplate("^socialscan(\\.py)?$", PluginInput.EMAIL, "python3 {file} {query}", PluginParser.PLAIN, "Socialscan")
    )

    fun templateFor(fileName: String): PluginTemplate? {
        val base = fileName.substringAfterLast('/').lowercase()
        return templates.firstOrNull { Regex(it.pattern).matches(base) }
    }

    /** Команда запуска: из шаблона, иначе по расширению (с пометкой «проверить»). */
    fun commandFor(fileName: String, query: String = "{query}"): Pair<String, Boolean> {
        val template = templateFor(fileName)
        if (template != null) {
            return (template.command.replace("{file}", fileName)
                .replace("{query}", query)) to true
        }
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val prefix = when (ext) {
            "sh" -> "bash"
            "js" -> "node"
            else -> "python3"
        }
        return "$prefix $fileName $query" to false
    }

    fun titleOf(fileName: String): String {
        val base = fileName.substringAfterLast('/').substringBeforeLast('.')
        return templateFor(fileName)?.pretty ?: base.replace('_', ' ').replace('-', ' ')
            .split(' ').filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    fun inputOf(fileName: String): PluginInput =
        templateFor(fileName)?.input ?: PluginInput.ANY

    fun parserOf(fileName: String): PluginParser =
        templateFor(fileName)?.parser ?: PluginParser.PLAIN

    /** Обход папки плагина: только скрипты, только безопасные пути. */
    fun scanDir(dir: File): List<PluginModule> {
        if (!dir.isDirectory) return emptyList()
        val files = dir.walkTopDown()
            .onEnter { it.name != ".git" }
            .filter { it.isFile && isScript(it.name) }
            .take(MAX_FILES)
            .toList()
        return files.mapIndexedNotNull { index, file ->
            val relative = file.relativeTo(dir).path.replace('\\', '/')
            if (safeRelative(relative) == null) return@mapIndexedNotNull null
            val (command, known) = commandFor(relative)
            PluginModule(
                id = "m${index + 1}_" + file.nameWithoutExtension.lowercase()
                    .replace(Regex("[^a-z0-9]+"), "_").take(24),
                title = titleOf(relative),
                file = relative,
                input = inputOf(relative),
                command = command,
                parser = parserOf(relative),
                enabled = false,
                known = known
            )
        }.distinctBy { it.file }
    }

    private val email = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private val host = Regex("(?i)\\b((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24})\\b")
    private val marked = Regex("^\\s*\\[[+*]]\\s*(.+)$")

    /** Разбор stdout: то, что скрипт напечатал, приводится к парам «тип → значение». */
    fun parseOutput(parser: PluginParser, stdout: String, limit: Int = 60): List<PluginHit> {
        val lines = stdout.replace("\r", "").lines()
        val hits = ArrayList<PluginHit>()
        when (parser) {
            PluginParser.EMAILS -> {
                email.findAll(stdout).forEach { hits += PluginHit("Почта", it.value.lowercase()) }
            }
            PluginParser.HOSTS -> {
                lines.forEach { line ->
                    val text = marked.find(line)?.groupValues?.get(1) ?: line
                    host.findAll(text).forEach { hits += PluginHit("Хост", it.value.lowercase()) }
                }
            }
            PluginParser.SHERLOCK -> {
                lines.forEach { line ->
                    val text = marked.find(line)?.groupValues?.get(1) ?: return@forEach
                    val url = Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',')
                    if (url != null) {
                        hits += PluginHit("Профиль", url)
                    } else {
                        val site = text.substringBefore(':').trim()
                        val value = text.substringAfter(':', "").trim()
                        if (site.isNotBlank() && value.isNotBlank()) hits += PluginHit("Профиль", "$site: $value")
                    }
                }
            }
            PluginParser.PLAIN -> {
                lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                    .forEach { hits += PluginHit("Результат", it.take(300)) }
            }
        }
        return hits.distinctBy { it.kind to it.value }.take(limit)
    }
}

/** Реестр подключённых репозиториев. Хранится в приватной папке приложения. */
class PluginStore(private val context: Context) {

    private val registry: File
        get() = File(context.filesDir, "plugins/registry.json")

    fun load(): List<InstalledPlugin> {
        val file = registry
        if (!file.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val plugins = root.optJSONArray("plugins") ?: JSONArray()
            (0 until plugins.length()).mapNotNull { i ->
                val node = plugins.optJSONObject(i) ?: return@mapNotNull null
                val modules = node.optJSONArray("modules") ?: JSONArray()
                InstalledPlugin(
                    slug = node.optString("slug"),
                    title = node.optString("title"),
                    source = node.optString("source"),
                    installedAt = node.optLong("installedAt"),
                    modules = (0 until modules.length()).mapNotNull { m ->
                        val item = modules.optJSONObject(m) ?: return@mapNotNull null
                        PluginModule(
                            id = item.optString("id"),
                            title = item.optString("title"),
                            file = item.optString("file"),
                            input = runCatching {
                                PluginInput.valueOf(item.optString("input"))
                            }.getOrDefault(PluginInput.ANY),
                            command = item.optString("command"),
                            parser = runCatching {
                                PluginParser.valueOf(item.optString("parser"))
                            }.getOrDefault(PluginParser.PLAIN),
                            enabled = item.optBoolean("enabled", false),
                            known = item.optBoolean("known", true)
                        )
                    }
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(plugins: List<InstalledPlugin>) {
        val root = JSONObject()
        root.put("schema", "jb-osint/plugins/1")
        val array = JSONArray()
        plugins.forEach { plugin ->
            val modules = JSONArray()
            plugin.modules.forEach { module ->
                modules.put(
                    JSONObject()
                        .put("id", module.id)
                        .put("title", module.title)
                        .put("file", module.file)
                        .put("input", module.input.name)
                        .put("command", module.command)
                        .put("parser", module.parser.name)
                        .put("enabled", module.enabled)
                        .put("known", module.known)
                )
            }
            array.put(
                JSONObject()
                    .put("slug", plugin.slug)
                    .put("title", plugin.title)
                    .put("source", plugin.source)
                    .put("installedAt", plugin.installedAt)
                    .put("modules", modules)
            )
        }
        root.put("plugins", array)
        val file = registry
        file.parentFile?.mkdirs()
        file.writeText(root.toString(2), Charsets.UTF_8)
    }

    fun remove(slug: String) {
        val left = load().filterNot { it.slug == slug }
        save(left)
        File(context.filesDir, "plugins/$slug").deleteRecursively()
    }

    fun toggle(slug: String, moduleId: String, enabled: Boolean): List<InstalledPlugin> {
        val updated = load().map { plugin ->
            if (plugin.slug != slug) plugin
            else plugin.copy(modules = plugin.modules.map {
                if (it.id == moduleId) it.copy(enabled = enabled) else it
            })
        }
        save(updated)
        return updated
    }

    fun setEnabled(slug: String, moduleIds: Set<String>, enabled: Boolean): List<InstalledPlugin> {
        val updated = load().map { plugin ->
            if (plugin.slug != slug) plugin
            else plugin.copy(modules = plugin.modules.map {
                if (moduleIds.isEmpty() || it.id in moduleIds) it.copy(enabled = enabled) else it
            })
        }
        save(updated)
        return updated
    }

    /** Папка плагина: только внутрь filesDir/plugins. */
    fun dirOf(slug: String): File? {
        if (!slug.matches(Regex("[a-z0-9][a-z0-9._-]{0,80}"))) return null
        return File(File(context.filesDir, "plugins"), slug)
    }
}

/**
 * Скачивание и разбор репозитория.
 *
 * Безопасность: качается только zip-архив с codeload.github.com; из архива
 * извлекаются ТОЛЬКО скрипты (.py/.sh/.js) и только по безопасным путям.
 * Ничего не запускается: установка заканчивается реестром модулей.
 */
class PluginInstaller(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun installFromGit(
        url: String,
        onProgress: (stage: String, percent: Int) -> Unit
    ): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        val repo = PluginRules.parseRepo(url)
            ?: return@withContext Result.failure(
                IllegalArgumentException("Нужна ссылка вида https://github.com/owner/repo")
            )
        runCatching {
            onProgress("Загрузка архива", 10)
            val bytes = download(PluginRules.zipUrl(repo))
            if (bytes.isEmpty()) throw IllegalStateException("Архив пустой")
            if (bytes.size > PluginRules.MAX_TOTAL_BYTES) throw IllegalStateException("Архив больше 40 МБ")

            onProgress("Распаковка скриптов", 45)
            val store = PluginStore(context)
            val target = store.dirOf(repo.slug) ?: throw IllegalStateException("Недопустимое имя репозитория")
            target.deleteRecursively()
            target.mkdirs()
            val extracted = unzipScripts(bytes, target)

            onProgress("Анализ скриптов", 80)
            val modules = scan(target).map { module ->
                module.copy(
                    command = module.command.replace("{dir}", target.absolutePath)
                )
            }
            if (modules.isEmpty()) throw IllegalStateException("В репозитории нет скриптов .py/.sh/.js")

            val plugin = InstalledPlugin(
                slug = repo.slug,
                title = repo.name,
                source = repo.page,
                installedAt = System.currentTimeMillis(),
                modules = modules
            )
            val existing = store.load().filterNot { it.slug == plugin.slug }
            store.save(existing + plugin)
            onProgress("Готово: $extracted файлов, ${modules.size} модулей", 100)
            plugin
        }
    }

    private fun download(url: String): ByteArray {
        val request = Request.Builder().url(url)
            .header("User-Agent", "JB-OSINT")
            .header("Accept", "application/zip")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("GitHub ответил ${response.code}")
            val body = response.body ?: throw IllegalStateException("Пустой ответ")
            val declared = body.contentLength()
            if (declared > PluginRules.MAX_TOTAL_BYTES) throw IllegalStateException("Архив больше 40 МБ")
            val stream = body.byteStream()
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                total += read
                if (total > PluginRules.MAX_TOTAL_BYTES) throw IllegalStateException("Архив больше 40 МБ")
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    /** Извлекаются только скрипты; бинарники, гифки и .git в папку не попадают. */
    private fun unzipScripts(bytes: ByteArray, target: File): Int {
        var files = 0
        var total = 0L
        var seen = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                seen++
                if (seen > PluginRules.MAX_FILES) break
                if (entry.isDirectory) continue
                val raw = entry.name.replace('\\', '/')
                val withoutRoot = raw.substringAfter('/', raw)
                if (withoutRoot.contains("__MACOSX")) continue
                val base = withoutRoot.substringAfterLast('/')
                if (!PluginRules.isScript(base)) continue
                if (entry.size > PluginRules.MAX_FILE_BYTES) continue
                val relative = PluginRules.safeRelative(withoutRoot) ?: continue
                val outFile = File(target, relative)
                val canonical = outFile.canonicalFile
                if (!canonical.path.startsWith(target.canonicalFile.path + File.separator)) continue
                canonical.parentFile?.mkdirs()
                val data = readLimited(zip, PluginRules.MAX_FILE_BYTES)
                if (data == null) continue
                total += data.size
                if (total > PluginRules.MAX_TOTAL_BYTES) break
                canonical.writeBytes(data)
                files++
            }
        }
        return files
    }

    private fun readLimited(zip: ZipInputStream, limit: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val read = zip.read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Сканирование папки: скрипт → модуль по шаблону. Файлы не исполняются. */
    fun scan(dir: File): List<PluginModule> = PluginRules.scanDir(dir)
}
