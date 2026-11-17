package ru.jbosint.osint.profile

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.random.Random

/**
 * Импорт выгрузки сообщений и демонстрационный набор.
 *
 * Приложение не хранит переписку: сообщения появляются в профиле только если
 * пользователь сам загрузил выгрузку (CSV/JSON из Telegram Desktop, экспорт
 * бота, собственный парсер). Формат определяется по заголовку CSV, поэтому
 * подходят выгрузки с колонками «Дата/Время/Отправитель/Текст», «date/time/
 * author/text» и англоязычные варианты.
 */
object ProfileMessages {

    data class ImportResult(val messages: List<ProfileMessage>, val skipped: Int, val error: String? = null)

    private val dateTimeFormats = listOf(
        "dd.MM.yyyy HH:mm:ss", "dd.MM.yyyy HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm",
        "dd/MM/yyyy HH:mm:ss", "MM/dd/yyyy HH:mm:ss", "dd.MM.yyyy", "yyyy-MM-dd"
    )

    /** Разбор CSV: разделитель «;» или «,», кавычки как в RFC 4180. */
    fun parseCsv(raw: String): ImportResult {
        val text = raw.removePrefix("\uFEFF")
        val rows = splitCsv(text)
        if (rows.isEmpty()) return ImportResult(emptyList(), 0, "Файл пуст")
        val header = rows.first().map { it.trim().lowercase() }
        val dateIdx = header.indexOfFirst { it in setOf("дата", "date", "day") }
        val timeIdx = header.indexOfFirst { it in setOf("время", "time") }
        val authorIdx = header.indexOfFirst { it in setOf("отправитель", "author", "from", "user", "sender", "имя") }
        val textIdx = header.indexOfFirst { it in setOf("текст", "text", "message", "сообщение", "content") }
        val typeIdx = header.indexOfFirst { it in setOf("тип", "type", "media", "медиа") }
        val replyIdx = header.indexOfFirst {
            it in setOf("кому", "кому ответ", "в ответ", "reply", "reply_to", "ответ", "in reply to")
        }
        if (dateIdx < 0 || textIdx < 0) {
            return ImportResult(emptyList(), 0, "Нужны колонки «Дата» и «Текст» (или date/text)")
        }
        val out = ArrayList<ProfileMessage>()
        var skipped = 0
        rows.drop(1).forEach { row ->
            val date = row.getOrNull(dateIdx).orEmpty().trim()
            val time = if (timeIdx >= 0) row.getOrNull(timeIdx).orEmpty().trim() else ""
            val at = parseDate(if (time.isNotBlank()) "$date $time" else date)
            val body = row.getOrNull(textIdx).orEmpty()
            if (at == null || body.isBlank()) { skipped++; return@forEach }
            out += ProfileMessage(
                at = at,
                author = if (authorIdx >= 0) row.getOrNull(authorIdx).orEmpty().trim() else "",
                text = body,
                type = if (typeIdx >= 0) MsgType.from(row.getOrNull(typeIdx).orEmpty()) else detectType(body),
                replyTo = if (replyIdx >= 0) row.getOrNull(replyIdx).orEmpty().trim() else ""
            )
        }
        if (out.isEmpty()) return ImportResult(emptyList(), skipped, "Не удалось разобрать ни одной строки")
        return ImportResult(out.sortedBy { it.at }, skipped)
    }

    /** Разбор JSON-массива сообщений: [{date|at|timestamp, author|from, text, type, reply_to}]. */
    fun parseJson(raw: String): ImportResult {
        val array = runCatching { JSONArray(raw) }.getOrElse {
            val root = runCatching { JSONObject(raw) }.getOrNull()
                ?: return ImportResult(emptyList(), 0, "Ожидался JSON-массив сообщений")
            root.optJSONArray("messages") ?: root.optJSONArray("items")
            ?: return ImportResult(emptyList(), 0, "В JSON нет массива messages")
        }
        val out = ArrayList<ProfileMessage>()
        var skipped = 0
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val raw1 = obj.optString("date").ifBlank { obj.optString("time") }
            val at = obj.optLong("at", 0L).takeIf { it > 0L }
                ?: obj.optLong("timestamp", 0L).takeIf { it > 0L }
                ?: parseDate(raw1)
            val body = obj.optString("text").ifBlank { obj.optString("message") }
            if (at == null || body.isBlank()) { skipped++; continue }
            out += ProfileMessage(
                at = at,
                author = obj.optString("author").ifBlank { obj.optString("from") },
                text = body,
                type = MsgType.from(obj.optString("type")),
                replyTo = obj.optString("reply_to").ifBlank { obj.optString("reply") },
                url = obj.optString("url")
            )
        }
        if (out.isEmpty()) return ImportResult(emptyList(), skipped, "Сообщения не распознаны")
        return ImportResult(out.sortedBy { it.at }, skipped)
    }

    fun parse(name: String, raw: String): ImportResult =
        if (name.trim().lowercase().endsWith(".json")) parseJson(raw) else parseCsv(raw)

    private fun detectType(text: String): MsgType = when {
        text.contains("http://") || text.contains("https://") -> MsgType.LINK
        text.startsWith("[фото]") || text.startsWith("[photo]") -> MsgType.PHOTO
        text.startsWith("[видео]") || text.startsWith("[video]") -> MsgType.VIDEO
        text.startsWith("[голосовое]") || text.startsWith("[voice]") -> MsgType.VOICE
        text.startsWith("[кружок]") || text.startsWith("[round]") -> MsgType.ROUND
        text.startsWith("[стикер]") || text.startsWith("[sticker]") -> MsgType.STICKER
        text.startsWith("[файл]") || text.startsWith("[file]") -> MsgType.FILE
        else -> MsgType.TEXT
    }

    private fun parseDate(raw: String): Long? {
        var clean = raw.trim().replace("T", " ")
        // Отрезаем миллисекунды вида «10:15:00.123», не трогая точки в дате.
        if (clean.count { it == ':' } >= 2 && Regex("\\.\\d{1,6}$").containsMatchIn(clean)) {
            clean = clean.replace(Regex("\\.\\d{1,6}$"), "")
        }
        dateTimeFormats.forEach { pattern ->
            runCatching {
                val format = SimpleDateFormat(pattern, Locale("ru")).apply { isLenient = true }
                format.parse(clean)?.time
            }.getOrNull()?.let { return it }
        }
        return null
    }

    /** CSV-парсер с поддержкой кавычек и переносов строк внутри значения. */
    private fun splitCsv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            when {
                quoted && ch == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                    cell.append('"'); index++
                }
                ch == '"' -> quoted = !quoted
                !quoted && (ch == ';' || ch == ',' || ch == '\t') -> {
                    row.add(cell.toString()); cell.setLength(0)
                }
                !quoted && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    row.add(cell.toString()); cell.setLength(0)
                    if (row.any { it.isNotBlank() }) rows.add(row)
                    row = ArrayList()
                }
                else -> cell.append(ch)
            }
            index++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            if (row.any { it.isNotBlank() }) rows.add(row)
        }
        return rows
    }

    /**
     * Демонстрационный набор: показывает все блоки профиля без ключей и сети.
     * Используется в демо-режиме (настройки → «Демо-данные»).
     */
    fun demo(): List<ProfileMessage> {
        val rnd = Random(20261117)
        val authors = listOf(
            "Марина К.", "Dmitry", "Алексей П.", "olga_77", "Sergey", "Ирина", "Nikita", "boss_alex"
        )
        val texts = listOf(
            "Привет, посмотри проект до вечера, там правки по API",
            "созвон в 19:00, скинь ссылку в зум",
            "оплата прошла, чек в личке",
            "сервер опять отвалился, проверь логи nginx",
            "спасибо, всё работает после фикса!",
            "не могу зайти в кабинет, пишет ошибку 500",
            "держи https://jbvpn.ru/docs/api — тут описания методов",
            "новости жёсткие, лучше не читать перед сном",
            "ахахах этот мем топ, сохранил",
            "клиент просит добавить подписку на 3 месяца",
            "устал, беру отпуск на неделю",
            "какой vpn посоветуешь для телефона?",
            "настроил REALITY на новом узле, скорость выросла",
            "плохо, что упал биллинг, нужно срочно починить",
            "ты дурак? я же просил не трогать прод",
            "поздравляю с релизом приложения!",
            "проверь домен fi.jbvpn.ru, сертификат истекает",
            "мем про котов скинул в канал",
            "сегодня дома, работаю удалённо",
            "нужен отчёт по затратам за месяц",
            "купил новый телефон, переношу аккаунты",
            "https://github.com/Djygas/jb-osint/issues — тут баг про экспорт",
            "сделал выгрузку в Excel, всё сходится",
            "не работает голосовое, попробуй перезаписать",
            "давай завтра, сегодня уже поздно"
        )
        val types = listOf(
            MsgType.TEXT, MsgType.TEXT, MsgType.TEXT, MsgType.LINK, MsgType.PHOTO,
            MsgType.VOICE, MsgType.ROUND, MsgType.VIDEO, MsgType.STICKER, MsgType.FILE
        )
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -45)
        calendar.set(Calendar.HOUR_OF_DAY, 9)
        var time = calendar.timeInMillis
        val out = ArrayList<ProfileMessage>()
        repeat(180) { index ->
            // рабочие часы чаще, ночь редко
            val hour = when (rnd.nextInt(100)) {
                in 0..9 -> rnd.nextInt(2) + 0
                in 10..19 -> rnd.nextInt(2) + 6
                in 20..84 -> 9 + rnd.nextInt(10)
                in 85..94 -> 20 + rnd.nextInt(3)
                else -> 23
            }
            calendar.timeInMillis = time
            calendar.set(Calendar.HOUR_OF_DAY, hour)
            calendar.set(Calendar.MINUTE, rnd.nextInt(60))
            time = calendar.timeInMillis + (1000L * 60 * (30 + rnd.nextInt(600)))
            val text = texts[index % texts.size]
            val reply = if (rnd.nextInt(100) < 28) authors[rnd.nextInt(authors.size)] else ""
            val type = when {
                text.contains("http") -> MsgType.LINK
                rnd.nextInt(100) < 22 -> types[rnd.nextInt(types.size)]
                else -> MsgType.TEXT
            }
            out += ProfileMessage(
                at = time,
                author = if (rnd.nextInt(100) < 55) "Объект" else authors[rnd.nextInt(authors.size)],
                text = text,
                type = type,
                replyTo = reply
            )
        }
        return out.sortedBy { it.at }
    }
}
