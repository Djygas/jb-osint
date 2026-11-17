package ru.jbosint.osint.report

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.jbosint.osint.BuildConfig
import ru.jbosint.osint.core.model.ReportMeta
import ru.jbosint.osint.core.report.HtmlReport
import ru.jbosint.osint.core.report.JsonReport
import ru.jbosint.osint.core.report.PdfReport
import ru.jbosint.osint.core.report.ReportFonts
import ru.jbosint.osint.core.report.ReportNaming
import ru.jbosint.osint.core.report.XlsxReport
import ru.jbosint.osint.data.HistoryEntry
import java.io.File

/**
 * Формирование отчётов и публикация файлов.
 *
 * 1. Файл всегда пишется в приватный cache/share — из него строится FileProvider Uri
 *    для Android Share Sheet.
 * 2. Дополнительно копия публикуется в общую папку JB_OSINT_Reports
 *    (Documents/JB_OSINT_Reports на Android 10+, либо на внешнем накопителе на 8–9).
 */
class ReportExporter(private val context: Context) {

    enum class Format(val extension: String, val mime: String, val label: String) {
        PDF("pdf", "application/pdf", "PDF"),
        XLSX(
            "xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "Excel"
        ),
        JSON("json", "application/json", "JSON"),
        HTML("html", "text/html", "HTML")
    }

    data class Exported(
        val uri: Uri,
        val mime: String,
        val fileName: String,
        val format: Format?,
        val publicFolder: String?
    )

    @Volatile
    private var cachedFonts: ReportFonts? = null

    suspend fun export(entry: HistoryEntry, format: Format): Exported = withContext(Dispatchers.IO) {
        val meta = ReportMeta(
            analyst = entry.analyst,
            reportId = ReportNaming.reportId(entry.query, entry.createdAt),
            createdAt = entry.createdAt,
            appVersion = BuildConfig.VERSION_NAME,
            aiAnalysis = entry.aiAnalysis,
            aiNarrative = entry.aiNarrative,
            aiNotes = entry.aiNotes,
            queryType = entry.queryType,
            photoNote = entry.photoNote
        )
        val fileName = ReportNaming.fileName(meta, entry.query, format.extension)
        val bytes = when (format) {
            Format.PDF -> PdfReport(meta, entry.data, fonts()).build()
            Format.XLSX -> XlsxReport(meta, entry.data).build()
            Format.JSON -> JsonReport(meta, entry.data).build().toByteArray(Charsets.UTF_8)
            Format.HTML -> HtmlReport(meta, entry.data, intro = entry.photoNote)
                .build().toByteArray(Charsets.UTF_8)
        }

        deliver(fileName, bytes, format)
    }

    /**
     * Сохраняет произвольный файл (HTML-отчёт Telegram-парсера, CSV-выгрузку аудитории)
     * по тем же правилам: приватная копия для шаринга + публикация в JB_OSINT_Reports.
     */
    suspend fun save(fileName: String, bytes: ByteArray, mime: String): Exported =
        withContext(Dispatchers.IO) {
            deliver(fileName, bytes, format = null, mime = mime)
        }

    private fun deliver(
        fileName: String,
        bytes: ByteArray,
        format: Format?,
        mime: String = format?.mime ?: "application/octet-stream"
    ): Exported {
        // 1) приватная копия для шаринга
        val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
        // чистим старые файлы, чтобы кеш не разрастался
        shareDir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000L) it.delete() }
        val shareFile = File(shareDir, fileName).apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", shareFile)

        // 2) копия в пользовательскую папку отчётов
        val publicFolder = runCatching { publish(fileName, mime, bytes) }.getOrNull()

        return Exported(uri, mime, fileName, format, publicFolder)
    }

    /** Публикация в общую папку JB_OSINT_Reports. Возвращает отображаемый путь или null. */
    private fun publish(fileName: String, mime: String, bytes: ByteArray): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOCUMENTS}/$PUBLIC_DIR"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val item = resolver.insert(collection, values) ?: return appPrivateFallback(fileName, bytes)
            try {
                resolver.openOutputStream(item)?.use { it.write(bytes) } ?: error("Нет доступа к потоку")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(item, values, null, null)
            } catch (t: Throwable) {
                runCatching { resolver.delete(item, null, null) }
                return appPrivateFallback(fileName, bytes)
            }
            return "${Environment.DIRECTORY_DOCUMENTS}/$PUBLIC_DIR"
        }

        // Android 8–9: обычная запись в Documents, нужен WRITE_EXTERNAL_STORAGE.
        val granted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return appPrivateFallback(fileName, bytes)
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            PUBLIC_DIR
        )
        if (!dir.exists() && !dir.mkdirs()) return appPrivateFallback(fileName, bytes)
        val out = File(dir, fileName)
        out.writeBytes(bytes)
        MediaScannerConnection.scanFile(context, arrayOf(out.absolutePath), arrayOf(mime), null)
        return "Documents/$PUBLIC_DIR (${out.parent})"
    }

    /** Запасной вариант без разрешений: личная папка приложения. */
    private fun appPrivateFallback(fileName: String, bytes: ByteArray): String? {
        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: return null,
            PUBLIC_DIR
        )
        if (!dir.exists() && !dir.mkdirs()) return null
        File(dir, fileName).writeBytes(bytes)
        return dir.absolutePath
    }

    private fun fonts(): ReportFonts {
        cachedFonts?.let { return it }
        synchronized(this) {
            cachedFonts?.let { return it }
            fun read(name: String): ByteArray =
                context.assets.open("fonts/$name").use { it.readBytes() }
            val parsed = ReportFonts.fromBytes(
                read("Inter-Regular.ttf"),
                read("Inter-SemiBold.ttf"),
                read("Inter-Bold.ttf")
            )
            cachedFonts = parsed
            return parsed
        }
    }

    companion object {
        const val PUBLIC_DIR = "JB_OSINT_Reports"
    }
}
