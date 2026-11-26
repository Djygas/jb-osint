package ru.jbosint.lab.server

import android.content.Context
import android.os.Environment
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import ru.jbosint.lab.LabToken
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** Ответ встроенного HTTP-сервера. */
data class LabHttpResponse(
    val status: Int,
    val body: String,
    val contentType: String = "application/json; charset=utf-8"
)

class LabException(message: String) : Exception(message)

/**
 * Маршруты встроенной лаборатории: /health, /clone, /scan, /run, /clear, /export.
 *
 * Сервер живёт в процессе приложения, слушает только 127.0.0.1 и принимает
 * запросы с внутренним токеном (он создаётся автоматически). Репозиторий
 * скачивается как ZIP с codeload.github.com и распаковывается в filesDir/lab.
 */
object LabApiModule {

    const val VERSION = "1.0.0"
    private const val MAX_ZIP = 60L * 1024 * 1024
    private const val MAX_FILE = 5L * 1024 * 1024
    private const val MAX_OUTPUT = 200_000
    private const val MAX_FILES = 3000

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun handle(
        context: Context,
        method: String,
        path: String,
        headers: Map<String, String>,
        body: String
    ): LabHttpResponse = try {
        if (!authorized(context, headers["x-lab-token"])) {
            error(401, "Неверный токен лаборатории")
        } else {
            when {
                path == "/health" && method == "GET" -> ok(
                    JSONObject().put("ok", true).put("version", VERSION)
                )
                path == "/clone" && method == "POST" -> cloneRepo(context, JSONObject(body))
                path == "/scan" && method == "POST" -> scanProject(context, JSONObject(body))
                path == "/run" && method == "POST" -> runCommand(context, JSONObject(body))
                path == "/clear" && method == "POST" -> clearProject(context, JSONObject(body))
                path == "/export" && method == "POST" -> exportProject(context, JSONObject(body))
                else -> error(404, "Неизвестный маршрут")
            }
        }
    } catch (e: LabException) {
        error(400, e.message ?: "Ошибка лаборатории")
    } catch (e: Throwable) {
        error(500, e.message ?: "Внутренняя ошибка")
    }

    private fun authorized(context: Context, incoming: String?): Boolean {
        val expected = LabToken.read(context)
        if (expected.isBlank() || incoming.isNullOrBlank()) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            incoming.toByteArray(Charsets.UTF_8)
        )
    }

    // ------------------------------------------------------------------ clone

    private fun cloneRepo(context: Context, body: JSONObject): LabHttpResponse {
        val repo = LabRules.parseRepo(body.optString("url"), body.optString("ref"))
            ?: throw LabException("Только https://github.com/owner/repo и корректная ветка")
        val url = "https://github.com/${repo.owner}/${repo.name}"
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("$url@${repo.ref}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
        val root = labRoot(context)
        val dir = File(root, hash)
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()

        val bytes = downloadArchive(repo)
        extract(bytes, dir)
        appendLog(dir, "# clone ${repo.owner}/${repo.name}@${repo.ref.ifBlank { "default" }}")

        return ok(
            JSONObject()
                .put("url", url)
                .put("ref", repo.ref)
                .put("dir", dir.absolutePath)
                .put("type", projectType(dir))
                .put("scripts", scanScripts(dir))
        )
    }

    private fun downloadArchive(repo: LabRepo): ByteArray {
        val base = "https://codeload.github.com/${repo.owner}/${repo.name}/zip"
        val candidates = buildList {
            if (repo.ref.isNotBlank()) {
                add("$base/${repo.ref}")
                add("$base/refs/heads/${repo.ref}")
            }
            add("$base/HEAD")
        }
        var last = "не удалось скачать архив"
        for (url in candidates) {
            val response = runCatching {
                client.newCall(
                    Request.Builder().url(url).header("User-Agent", "JB-OSINT-Lab").build()
                ).execute()
            }.getOrElse { last = it.message ?: "сеть недоступна"; null } ?: continue
            response.use {
                if (it.isSuccessful) {
                    val stream = it.body?.byteStream() ?: throw LabException("Пустой архив")
                    return readLimited(stream, MAX_ZIP) ?: throw LabException("Архив больше 60 МБ")
                }
                last = "GitHub ответил ${it.code}"
            }
        }
        throw LabException(last)
    }

    private fun extract(bytes: ByteArray, dir: File) {
        val root = dir.canonicalFile
        var total = 0L
        var entries = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                if (entries > MAX_FILES) break
                if (entry.isDirectory) continue
                val raw = entry.name.replace('\\', '/')
                val parts = raw.split('/').filter { it.isNotBlank() }
                if (parts.size < 2) continue
                val relative = parts.drop(1).joinToString("/")
                if (relative.contains("..") || relative.startsWith("/")) continue
                if (relative.startsWith(".git/")) continue
                val out = File(dir, relative).canonicalFile
                if (!out.path.startsWith(root.path + File.separator)) continue
                val data = readLimited(zip, MAX_FILE) ?: continue
                total += data.size
                if (total > MAX_ZIP) break
                out.parentFile?.mkdirs()
                out.writeBytes(data)
            }
        }
    }

    // ------------------------------------------------------------------- scan

    private fun scanProject(context: Context, body: JSONObject): LabHttpResponse {
        val dir = safeDir(context, body.optString("dir"))
        return ok(
            JSONObject()
                .put("dir", dir.absolutePath)
                .put("type", projectType(dir))
                .put("scripts", scanScripts(dir))
        )
    }

    private fun projectType(dir: File): String {
        val names = dir.listFiles()?.filter { it.isFile }?.map { it.name }?.toSet().orEmpty()
        return LabRules.projectType(names)
    }

    private fun scanScripts(dir: File): JSONArray {
        val array = JSONArray()
        val skip = setOf(".git", ".gradle", "build", "node_modules", "__pycache__", ".venv", "venv", "dist")
        dir.walkTopDown().maxDepth(3).forEach { file ->
            if (array.length() >= 80) return@forEach
            if (!file.isFile) return@forEach
            val relative = file.relativeTo(dir).path.replace(File.separatorChar, '/')
            if (relative.split('/').any { it in skip }) return@forEach
            val type = LabRules.scriptType(relative) ?: return@forEach
            val lines = runCatching { file.useLines { it.take(40).toList() } }.getOrDefault(emptyList())
            val meta = LabRules.buttonMeta(lines)
            array.put(
                JSONObject()
                    .put("name", file.name)
                    .put("path", relative)
                    .put("type", type)
                    .put("button", meta.title ?: JSONObject.NULL)
                    .put("param", meta.param ?: JSONObject.NULL)
            )
        }
        return array
    }

    // -------------------------------------------------------------------- run

    private fun runCommand(context: Context, body: JSONObject): LabHttpResponse {
        val dir = safeDir(context, body.optString("dir"))
        val timeout = body.optInt("timeout", 600).coerceIn(1, 1800)
        val command = body.optString("command").trim()
        if (command.isEmpty()) throw LabException("Пустая команда")
        if (command.length > 4000) throw LabException("Команда слишком длинная")
        val args = LabRules.splitArgs(command)
        if (args.isEmpty()) throw LabException("Пустая команда")
        val first = args[0]
        val name = first.substringAfterLast('/').lowercase(Locale.US)
        if (LabRules.isBlocked(first)) throw LabException("sudo/su/doas/rm запрещены")

        val argv: List<String>
        when {
            name == "sh" || name == "bash" -> {
                val script = resolveInside(dir, args.getOrNull(1))
                argv = listOf("/system/bin/sh", script.absolutePath) + args.drop(2)
            }
            first.startsWith("./") -> {
                val script = resolveInside(dir, first)
                argv = listOf("/system/bin/sh", script.absolutePath) + args.drop(1)
            }
            name == "python" || name == "python3" -> {
                val python = listOf("/system/bin/python3", "/system/bin/python", "/usr/bin/python3")
                    .firstOrNull { File(it).canExecute() }
                    ?: return ok(
                        JSONObject()
                            .put("exit_code", 127)
                            .put("stdout", "Python не установлен на устройстве. Поддерживаются .sh-скрипты Android shell.")
                            .put("stderr", "")
                            .put("took_ms", 0)
                    )
                val script = resolveInside(dir, args.getOrNull(1))
                argv = listOf(python, script.absolutePath) + args.drop(2)
            }
            else -> {
                if (!LabRules.isAllowed(first)) throw LabException("Команда $name не в белом списке")
                val binary = findBinary(name) ?: throw LabException("Команда $name недоступна на устройстве")
                argv = listOf(binary) + args.drop(1)
            }
        }

        val started = System.currentTimeMillis()
        val process = runCatching {
            ProcessBuilder(argv)
                .directory(dir)
                .redirectErrorStream(true)
                .apply { environment()["PATH"] = "/system/bin:/system/xbin" }
                .start()
        }.getOrElse { throw LabException("Не удалось запустить: ${it.message}") }

        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (output.length < MAX_OUTPUT) output.append(line).append('\n')
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val finished = process.waitFor(timeout.toLong(), TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(2000)
        val took = System.currentTimeMillis() - started
        val code = if (finished) process.exitValue() else 124
        val text = output.toString()
        appendLog(dir, "$ $command\n$text# exit $code, $took ms")

        return ok(
            JSONObject()
                .put("exit_code", code)
                .put("stdout", text.take(MAX_OUTPUT))
                .put("stderr", "")
                .put("took_ms", took)
        )
    }

    private fun resolveInside(dir: File, raw: String?): File {
        if (raw.isNullOrBlank()) throw LabException("Укажите файл скрипта")
        val candidate = File(raw)
        val file = if (candidate.isAbsolute) candidate.canonicalFile else File(dir, raw).canonicalFile
        val root = dir.canonicalFile
        if (!file.path.startsWith(root.path + File.separator)) throw LabException("Скрипт вне проекта")
        if (!file.isFile) throw LabException("Файл не найден: $raw")
        return file
    }

    private fun findBinary(name: String): String? =
        listOf("/system/bin/$name", "/system/xbin/$name").firstOrNull { File(it).canExecute() }

    // ------------------------------------------------------------------ clear

    private fun clearProject(context: Context, body: JSONObject): LabHttpResponse {
        val dir = safeDir(context, body.optString("dir"))
        dir.deleteRecursively()
        return ok(JSONObject().put("ok", true))
    }

    // ----------------------------------------------------------------- export

    private fun exportProject(context: Context, body: JSONObject): LabHttpResponse {
        val dir = safeDir(context, body.optString("dir"))
        val base = context.getExternalFilesDir(null) ?: File(context.filesDir, "exports")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val target = File(base, "JB-Osint/reports/${dir.name}-$stamp")
        target.mkdirs()
        File(dir, "lab.log").takeIf { it.isFile }?.copyTo(File(target, "lab.log"), overwrite = true)
        var copied = 0
        dir.walkTopDown().forEach { file ->
            if (copied >= 50) return@forEach
            if (file.isFile && file.extension.lowercase(Locale.US) in setOf("apk", "jar")) {
                runCatching { file.copyTo(File(target, file.name), overwrite = true); copied++ }
            }
        }
        return ok(JSONObject().put("path", target.absolutePath).put("files", copied))
    }

    // ----------------------------------------------------------------- helpers

    private fun labRoot(context: Context): File =
        File(context.filesDir, "lab").apply { mkdirs() }

    private fun safeDir(context: Context, raw: String): File {
        val root = labRoot(context).canonicalFile
        val dir = runCatching { File(raw).canonicalFile }.getOrNull()
            ?: throw LabException("Некорректный каталог")
        if (!dir.path.startsWith(root.path + File.separator) || !dir.isDirectory) {
            throw LabException("Каталог вне лаборатории")
        }
        return dir
    }

    private fun appendLog(dir: File, text: String) {
        runCatching {
            File(dir, "lab.log").appendText(text.trimEnd() + "\n")
        }
    }

    private fun readLimited(input: InputStream, limit: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun ok(json: JSONObject) = LabHttpResponse(200, json.toString())

    private fun error(status: Int, message: String) =
        LabHttpResponse(status, JSONObject().put("detail", message).toString())
}
