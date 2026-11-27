package ru.jbosint.osint.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader

/**
 * Локальный индекс дампов и кэш ответов API.
 *
 * Индекс устроен так, чтобы поиск по 500 МБ дампу укладывался в секунды:
 * при индексации считаются только строки с адресами, а сам поиск идёт по
 * индексу `hash` в SQLite, а не чтением файлов.
 *
 * Хранится: SHA-256 адреса, маска, файл, строка, происхождение и (для строк
 * вида `email:password`) сам пароль. Пароль показывается и экспортируется только
 * при включённом режиме «Показывать пароли».
 */
class LeakStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DB_NAME, null, DB_VERSION
) {

    companion object {
        private const val DB_NAME = "leaks.db"
        private const val DB_VERSION = 3

        /** Папка дампов внутри приватного хранилища приложения. */
        fun leaksDir(context: Context): File =
            File(context.applicationContext.filesDir, "leaks").apply { mkdirs() }

        /** Имя файла из SAF-ссылки: без путей, только безопасные символы. */
        fun safeName(raw: String): String {
            val base = raw.substringAfterLast('/').substringAfterLast('\\')
            val clean = base.map { ch ->
                if (ch.isLetterOrDigit() || ch in "._- ") ch else '_'
            }.joinToString("").trim()
            val name = clean.ifBlank { "leak.txt" }
            return if (name.length <= 80) name else name.take(76) + name.substringAfterLast('.', "txt")
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(false)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE files(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "path TEXT NOT NULL UNIQUE," +
                "size INTEGER NOT NULL," +
                "mtime INTEGER NOT NULL," +
                "lines INTEGER NOT NULL DEFAULT 0," +
                "secrets INTEGER NOT NULL DEFAULT 0," +
                "indexed_at INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE hits(" +
                "hash TEXT NOT NULL," +
                "file_id INTEGER NOT NULL," +
                "line INTEGER NOT NULL," +
                "kind TEXT NOT NULL," +
                "mask TEXT NOT NULL," +
                "has_secret INTEGER NOT NULL DEFAULT 0," +
                "password TEXT," +
                "origin TEXT," +
                "service TEXT," +
                "leak_date TEXT," +
                "data_type TEXT," +
                "source_url TEXT)"
        )
        db.execSQL("CREATE INDEX idx_leak_hash ON hits(hash)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_leak_origin ON hits(origin)")
        db.execSQL("CREATE TABLE cache(k TEXT PRIMARY KEY, body TEXT NOT NULL, created INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Миграция без пересоздания: добавляем колонки пароля и происхождения.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE hits ADD COLUMN password TEXT")
            db.execSQL("ALTER TABLE hits ADD COLUMN origin TEXT")
            // Старые строки индекса не содержат пароль/происхождение — помечаем файлы
            // изменёнными, чтобы ближайшая индексация перечитала их (таблицу не пересоздаём).
            runCatching { db.execSQL("UPDATE files SET mtime = -1") }
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE hits ADD COLUMN service TEXT")
            db.execSQL("ALTER TABLE hits ADD COLUMN leak_date TEXT")
            db.execSQL("ALTER TABLE hits ADD COLUMN data_type TEXT")
            db.execSQL("ALTER TABLE hits ADD COLUMN source_url TEXT")
            runCatching { db.execSQL("UPDATE files SET mtime = -1") }
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_leak_origin ON hits(origin)")
    }

    // ------------------------------------------------------------------ индексация

    /**
     * Индексирует папку. Файлы без изменений (тот же размер и mtime) пропускаются,
     * изменившиеся переиндексируются, исчезнувшие вычищаются из базы.
     */
    fun index(
        dir: File,
        force: Boolean = false,
        onStage: (String, Int) -> Unit = { _, _ -> }
    ): LeakIndexReport {
        val started = System.currentTimeMillis()
        dir.mkdirs()
        val files = collectFiles(dir)
        val db = writableDatabase

        // Чистим записи о файлах, которых больше нет.
        val alive = files.map { it.absolutePath }.toHashSet()
        runCatching {
            db.rawQuery("SELECT id, path FROM files", null).use { cursor ->
                val gone = ArrayList<Long>()
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) !in alive) gone += cursor.getLong(0)
                }
                gone.forEach { id ->
                    db.delete("hits", "file_id=?", arrayOf(id.toString()))
                    db.delete("files", "id=?", arrayOf(id.toString()))
                }
            }
        }

        var scanned = 0
        var skipped = 0
        var rows = 0L
        var secrets = 0L
        val errors = ArrayList<String>()

        files.forEachIndexed { index, file ->
            onStage(file.name, if (files.isEmpty()) 100 else index * 100 / files.size)
            val outcome = runCatching { indexFile(db, file, force) }
            outcome.onSuccess { report ->
                if (report == null) skipped++ else {
                    scanned++
                    rows += report.first
                    secrets += report.second
                }
            }.onFailure { error ->
                errors += "${file.name}: ${error.message ?: "ошибка чтения"}"
            }
        }
        onStage("Готово", 100)

        val stats = stats()
        return LeakIndexReport(
            scanned = scanned,
            skipped = skipped,
            rows = rows,
            files = stats.files,
            secrets = secrets,
            tookMs = System.currentTimeMillis() - started,
            errors = errors
        )
    }

    /** @return null — файл не изменился; пара (строк в индексе, строк с паролем) — если проиндексирован. */
    private fun indexFile(db: SQLiteDatabase, file: File, force: Boolean): Pair<Long, Long>? {
        val path = file.absolutePath
        val size = file.length()
        val mtime = file.lastModified()

        var fileId = 0L
        var known = false
        db.rawQuery("SELECT id, size, mtime FROM files WHERE path=?", arrayOf(path)).use { cursor ->
            if (cursor.moveToFirst()) {
                fileId = cursor.getLong(0)
                known = cursor.getLong(1) == size && cursor.getLong(2) == mtime
            }
        }
        if (known && !force) return null

        if (fileId == 0L) {
            val values = android.content.ContentValues().apply {
                put("path", path)
                put("size", size)
                put("mtime", mtime)
            }
            fileId = db.insert("files", null, values)
        } else {
            db.delete("hits", "file_id=?", arrayOf(fileId.toString()))
        }
        if (fileId <= 0L) return 0L to 0L

        var lines = 0L
        var secrets = 0L
        var pending = 0
        val statement = db.compileStatement(
            "INSERT INTO hits(hash, file_id, line, kind, mask, has_secret, password, origin, " +
                "service, leak_date, data_type, source_url) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)"
        )

        var failure: Throwable? = null
        db.beginTransaction()
        try {
            BufferedReader(
                InputStreamReader(FileInputStream(file), Charsets.UTF_8),
                1 shl 20
            ).use { reader ->
                var lineNo = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    lineNo++
                    lines++
                    val emails = LeakRules.emailsIn(line)
                    val phone = if (emails.isEmpty()) LeakRules.phoneIn(line) else null
                    if (emails.isEmpty() && phone == null) continue

                    val origin = file.name
                    for (email in emails) {
                        val record = LeakRules.parseRecord(line, email)
                        val password = record.password
                        val secret = password != null || LeakRules.hasSecret(line, email)
                        if (secret) secrets++
                        statement.clearBindings()
                        statement.bindString(1, LeakRules.hash(email))
                        statement.bindLong(2, fileId)
                        statement.bindLong(3, lineNo.toLong())
                        statement.bindString(4, "email")
                        statement.bindString(5, LeakRules.mask(email))
                        statement.bindLong(6, if (secret) 1L else 0L)
                        if (password != null) statement.bindString(7, password) else statement.bindNull(7)
                        statement.bindString(8, origin)
                        statement.bindString(9, record.service.ifBlank { origin })
                        statement.bindString(10, record.date)
                        statement.bindString(11, record.dataType.ifBlank { if (password != null) "Email+Password" else "Email" })
                        statement.bindString(12, record.sourceUrl)
                        statement.executeInsert()
                        if (++pending >= LeakRules.CHUNK) {
                            db.setTransactionSuccessful()
                            db.endTransaction()
                            db.beginTransaction()
                            pending = 0
                        }
                    }
                    if (phone != null) {
                        statement.clearBindings()
                        statement.bindString(1, LeakRules.hash(phone))
                        statement.bindLong(2, fileId)
                        statement.bindLong(3, lineNo.toLong())
                        statement.bindString(4, "phone")
                        statement.bindString(5, LeakRules.mask(phone))
                        statement.bindLong(6, 0L)
                        statement.bindNull(7)
                        statement.bindString(8, origin)
                        statement.bindString(9, origin)
                        statement.bindString(10, "")
                        statement.bindString(11, "Phone")
                        statement.bindString(12, "")
                        statement.executeInsert()
                        if (++pending >= LeakRules.CHUNK) {
                            db.setTransactionSuccessful()
                            db.endTransaction()
                            db.beginTransaction()
                            pending = 0
                        }
                    }
                }
            }
            db.setTransactionSuccessful()
        } catch (error: Throwable) {
            failure = error
        } finally {
            runCatching { db.endTransaction() }
        }
        if (failure != null) {
            // Недочитанный файл не считаем проиндексированным — повторим в следующий раз.
            db.delete("hits", "file_id=?", arrayOf(fileId.toString()))
            db.delete("files", "id=?", arrayOf(fileId.toString()))
            throw failure
        }

        val done = android.content.ContentValues().apply {
            put("size", size)
            put("mtime", mtime)
            put("lines", lines)
            put("secrets", secrets)
            put("indexed_at", System.currentTimeMillis())
        }
        db.update("files", done, "id=?", arrayOf(fileId.toString()))
        return lines to secrets
    }

    private fun collectFiles(dir: File, depth: Int = 0): List<File> {
        if (depth > 2 || !dir.isDirectory) return emptyList()
        val out = ArrayList<File>()
        dir.listFiles()?.sortedBy { it.name }?.forEach { item ->
            when {
                item.isDirectory -> out += collectFiles(item, depth + 1)
                item.isFile && LeakRules.indexable(item.name) -> out += item
            }
        }
        return out
    }

    // ------------------------------------------------------------------ поиск и кэш

    /** Поиск по индексу: один индексный просмотр, файлы не читаются. */
    fun lookup(subject: String): List<LeakHit> {
        val hash = LeakRules.hash(subject)
        val out = ArrayList<LeakHit>()
        readableDatabase.rawQuery(
            "SELECT h.mask, h.line, h.kind, h.has_secret, f.path, h.password, h.origin, " +
                "h.service, h.leak_date, h.data_type, h.source_url FROM hits h " +
                "JOIN files f ON f.id = h.file_id WHERE h.hash = ? ORDER BY h.line LIMIT ?",
            arrayOf(hash, LeakRules.MAX_HITS.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += LeakHit(
                    file = cursor.getString(4),
                    line = cursor.getInt(1),
                    kind = cursor.getString(2),
                    mask = cursor.getString(0),
                    hasSecret = cursor.getInt(3) == 1,
                    password = cursor.getString(5),
                    origin = cursor.getString(6),
                    service = cursor.getString(7).orEmpty(),
                    leakDate = cursor.getString(8).orEmpty(),
                    dataType = cursor.getString(9).orEmpty(),
                    sourceUrl = cursor.getString(10).orEmpty()
                )
            }
        }
        return out
    }

    fun stats(): LeakIndexStats =
        readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(lines),0), COALESCE(SUM(size),0)," +
                " COALESCE(SUM(secrets),0), COALESCE(MAX(indexed_at),0) FROM files",
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) LeakIndexStats() else LeakIndexStats(
                files = cursor.getInt(0),
                rows = cursor.getLong(1),
                bytes = cursor.getLong(2),
                secrets = cursor.getLong(3),
                updatedAt = cursor.getLong(4)
            )
        }

    fun cacheGet(key: String, ttlMs: Long): String? =
        readableDatabase.rawQuery(
            "SELECT body, created FROM cache WHERE k=?", arrayOf(key)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val age = System.currentTimeMillis() - cursor.getLong(1)
            if (age > ttlMs) null else cursor.getString(0)
        }

    fun cachePut(key: String, body: String) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO cache(k, body, created) VALUES(?,?,?)",
            arrayOf<Any>(key, body, System.currentTimeMillis())
        )
    }

    /** Очистка индекса: сами файлы остаются, база строится заново. */
    fun clearIndex() {
        val db = writableDatabase
        db.delete("hits", null, null)
        db.delete("files", null, null)
    }

    fun clearCache() {
        writableDatabase.delete("cache", null, null)
    }
}
