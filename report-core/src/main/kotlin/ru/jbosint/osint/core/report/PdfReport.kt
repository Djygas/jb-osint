package ru.jbosint.osint.core.report

import ru.jbosint.osint.core.model.AggregatedData
import ru.jbosint.osint.core.model.Confidence
import ru.jbosint.osint.core.model.Fact
import ru.jbosint.osint.core.model.FactCategory
import ru.jbosint.osint.core.model.Link
import ru.jbosint.osint.core.model.ReportMeta
import ru.jbosint.osint.core.pdf.PdfDocument
import ru.jbosint.osint.core.pdf.PdfFontHandle
import ru.jbosint.osint.core.pdf.PdfPage
import ru.jbosint.osint.core.pdf.Rgb
import ru.jbosint.osint.core.pdf.TtfFont
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Набор шрифтов отчёта (Inter: Regular / SemiBold / Bold). */
class ReportFonts(
    val regular: TtfFont,
    val semibold: TtfFont,
    val bold: TtfFont
) {
    companion object {
        fun fromBytes(regular: ByteArray, semibold: ByteArray, bold: ByteArray): ReportFonts = ReportFonts(
            TtfFont.parse(regular, "Inter-Regular"),
            TtfFont.parse(semibold, "Inter-SemiBold"),
            TtfFont.parse(bold, "Inter-Bold")
        )

        /** Ресурсы classpath — используется в тестах и на десктопе. */
        fun fromClasspath(): ReportFonts {
            fun read(name: String): ByteArray =
                ReportFonts::class.java.getResourceAsStream("/fonts/$name")?.use { it.readBytes() }
                    ?: error("Не найден шрифт $name")
            return fromBytes(read("Inter-Regular.ttf"), read("Inter-SemiBold.ttf"), read("Inter-Bold.ttf"))
        }
    }
}

private object Palette {
    val ink = Rgb.hex(0x14161C)
    val body = Rgb.hex(0x2B2F38)
    val muted = Rgb.hex(0x6B7280)
    val faint = Rgb.hex(0x9AA1AD)
    val line = Rgb.hex(0xE4E7EC)
    val zebra = Rgb.hex(0xF7F8FA)
    val paper = Rgb.hex(0xFFFFFF)
    val accent = Rgb.hex(0x4F5BFF)
    val accent2 = Rgb.hex(0x8B5CF6)
    val accentSoft = Rgb.hex(0xEEF0FF)
    val dark = Rgb.hex(0x0B0E14)
    val darkSoft = Rgb.hex(0x151A24)
    val onDark = Rgb.hex(0xEDEFF5)
    val onDarkMuted = Rgb.hex(0x8A93A6)
    val green = Rgb.hex(0x12885A)
    val amber = Rgb.hex(0xB45309)
    val slate = Rgb.hex(0x64748B)

    fun forConfidence(c: Confidence): Rgb = when (c) {
        Confidence.CONFIRMED -> green
        Confidence.PROBABLE -> amber
        Confidence.UNVERIFIED -> slate
    }
}

/**
 * Генератор аналитического отчёта PDF: титульный лист, методология,
 * профилирование связей, реестр фактов с оценкой достоверности, приложения.
 * Никаких упоминаний сторонних сервисов — только брендинг JB OSINT.
 */
class PdfReport(
    private val meta: ReportMeta,
    private val data: AggregatedData,
    private val fonts: ReportFonts
) {
    private val doc = PdfDocument()
    private val fReg: PdfFontHandle = doc.registerFont(fonts.regular, "F1")
    private val fSemi: PdfFontHandle = doc.registerFont(fonts.semibold, "F2")
    private val fBold: PdfFontHandle = doc.registerFont(fonts.bold, "F3")

    private val pageW = doc.pageWidth
    private val pageH = doc.pageHeight
    private val marginL = 56f
    private val contentW = pageW - marginL * 2
    private val contentTop = 92f
    private val contentBottom = pageH - 64f

    private lateinit var page: PdfPage
    private var y: Float = contentTop
    private var section: String = ""

    fun build(): ByteArray {
        titlePage()
        introSection()
        methodology()
        summarySection()
        telegramSection()
        socialSection()
        emailSection()
        leaksSection()
        linksSection()
        dossierSection()
        documentsSection()
        mediaSection()
        faceSection()
        aiSection()
        confidenceSection()
        limitationsSection()
        appendix()
        paginate()
        val info = linkedMapOf(
            "Title" to "JB OSINT — отчёт об исследовании открытых источников",
            "Author" to meta.analyst.ifBlank { "JB OSINT" },
            "Subject" to data.query,
            "Creator" to "JB OSINT ${meta.appVersion}",
            "Producer" to "JB OSINT PDF Engine",
            "Keywords" to "OSINT, отчёт, JB OSINT, ${data.query}"
        )
        return doc.write(info)
    }

    // ---------------------------------------------------------------- титул

    private fun titlePage() {
        page = doc.newPage()
        val p = page
        p.rect(0f, 0f, pageW, pageH, Palette.dark)

        // Техническая сетка.
        p.save()
        p.alpha(0.05f)
        var gx = 40f
        while (gx < pageW) {
            p.line(gx, 0f, gx, pageH, Palette.onDark, 0.5f)
            gx += 36f
        }
        var gy = 40f
        while (gy < pageH) {
            p.line(0f, gy, pageW, gy, Palette.onDark, 0.5f)
            gy += 36f
        }
        p.restore()

        // Свечение акцента.
        p.save()
        p.alpha(0.05f)
        p.circle(pageW - 70f, pageH - 210f, 190f, fill = Palette.accent2)
        p.alpha(0.06f)
        p.circle(pageW - 70f, pageH - 210f, 140f, fill = Palette.accent)
        p.alpha(0.07f)
        p.circle(pageW - 70f, pageH - 210f, 96f, fill = Palette.accent2)
        p.restore()

        // Верхняя строка.
        p.text(marginL, pageH - 46f, 6.4f, fSemi, "JB OSINT", Palette.onDark, 2.4f)
        p.text(marginL + 52f, pageH - 46f, 6.4f, fReg, "INTELLIGENCE PLATFORM", Palette.onDarkMuted, 2.4f)
        p.text(pageW - marginL - 108f, pageH - 46f, 6.4f, fReg, "REPORT ENGINE v${meta.appVersion}", Palette.onDarkMuted, 1.6f)

        // Логотип: гексагон с монограммой.
        val cx = marginL + 26f
        val cy = pageH - 250f
        drawHexagon(p, cx, cy, 26f, Palette.accent, 1.4f)
        p.text(cx - fBold.width("JB", 17f) / 2f, cy - 5.6f, 17f, fBold, "JB", Palette.onDark, 0.6f)

        // Вордмарк.
        p.text(marginL, pageH - 330f, 44f, fBold, "JB OSINT", Palette.onDark, 1.2f)
        p.rect(marginL, pageH - 352f, 56f, 2.6f, Palette.accent)

        p.text(marginL, pageH - 380f, 8.6f, fSemi, "ОТЧЁТ ОБ ИССЛЕДОВАНИИ ОТКРЫТЫХ ИСТОЧНИКОВ", Palette.accent, 1.9f)
        p.text(marginL, pageH - 398f, 7.2f, fReg, "Open Source Intelligence · аналитическая записка", Palette.onDarkMuted, 0.6f)

        // Объект запроса.
        p.text(marginL, pageH - 470f, 6.2f, fSemi, "ОБЪЕКТ ЗАПРОСА", Palette.onDarkMuted, 1.8f)
        val queryLines = wrap(data.query.ifBlank { "—" }, fBold, 24f, contentW - 40f).take(3)
        var qy = pageH - 496f
        for (line in queryLines) {
            p.text(marginL, qy, 24f, fBold, line, Palette.onDark, 0.2f)
            qy -= 28f
        }

        // Метаданные.
        val rows = listOf(
            "АНАЛИТИК" to meta.analyst.ifBlank { "Не указан" },
            "ДАТА ФОРМИРОВАНИЯ" to dateText(meta.createdAt),
            "ИДЕНТИФИКАТОР" to meta.reportId,
            "СВЕДЕНИЙ В РЕЕСТРЕ" to data.facts.size.toString(),
            "СВЯЗЕЙ ПОСТРОЕНО" to data.links.size.toString()
        )
        var my = pageH - 600f
        for ((label, value) in rows) {
            p.text(marginL, my, 6.2f, fSemi, label, Palette.onDarkMuted, 1.6f)
            p.text(marginL + 150f, my - 0.4f, 8.6f, fSemi, p.ellipsize(value, fSemi, 8.6f, contentW - 160f), Palette.onDark)
            p.save()
            p.alpha(0.12f)
            p.line(marginL, my - 9f, pageW - marginL, my - 10f, Palette.onDark, 0.5f)
            p.restore()
            my -= 27f
        }

        // Полоса классификации.
        p.rect(0f, 34f, pageW, 30f, Palette.accent)
        p.text(marginL, 52f, 6.2f, fSemi, meta.classification, Palette.paper, 1.7f)
        p.text(pageW - marginL - fReg.width("${data.stats.durationMs / 1000} с · ${data.stats.sourcesResponded} модулей сбора", 6.2f),
            52f, 6.2f, fReg, "${data.stats.durationMs / 1000} с · ${data.stats.sourcesResponded} модулей сбора", Palette.paper, 1.2f)

        p.text(marginL, 20f, 6f, fReg, "Сформировано автоматически в JB OSINT · ${meta.reportId}", Palette.onDarkMuted, 0.8f)
    }

    private fun drawHexagon(p: PdfPage, cx: Float, cy: Float, r: Float, color: Rgb, width: Float) {
        val points = ArrayList<Pair<Float, Float>>(6)
        for (i in 0 until 6) {
            val angle = Math.toRadians(60.0 * i - 90.0)
            points += Pair(cx + (r * cos(angle)).toFloat(), cy + (r * sin(angle)).toFloat())
        }
        p.polygon(points, strokeColor = color, strokeWidth = width)
    }

    // ---------------------------------------------------------------- введение

    private fun introSection() {
        contentPage("Введение")
        h1("0. Введение")
        p("Настоящий документ представляет собой полное досье, подготовленное по результатам " +
            "автоматизированного сбора и верификации сведений из открытых источников (OSINT). " +
            "Досье объединяет цифровые следы объекта, документальные данные, медиаматериалы и " +
            "аналитические выводы, а каждое сведение сопровождается указанием источника и ссылки " +
            "на страницу, где оно было обнаружено.")

        label("ОБЪЕКТ ИССЛЕДОВАНИЯ")
        callout("Запрос: «${data.query}». Категория запроса: ${meta.queryType.ifBlank { "не определена" }}. " +
            "Классификация документа: ${meta.classification}.")

        label("ОСНОВАНИЕ И ЦЕЛИ")
        bullet("Собрать и нормализовать сведения об объекте из открытых источников.")
        bullet("Установить и визуализировать связи между идентификаторами объекта.")
        bullet("Оценить достоверность каждого сведения по числу независимых подтверждений.")
        bullet("Сформировать доказательную базу: для каждого факта — источник, ссылка, дата и статус.")
        bullet("Подготовить аналитическое заключение и рекомендации по дальнейшей проверке.")

        label("ИСПОЛЬЗОВАННЫЕ ИСТОЧНИКИ (${sources().size})")
        gap(4f)
        val used = sources()
        if (used.isEmpty()) {
            p("Модули сбора не зафиксировали источник — сведения получены встроенным поиском JB OSINT.", color = Palette.muted)
        } else {
            table(
                columns = listOf(
                    Column("№", 20f),
                    Column("Модуль", 150f),
                    Column("Ресурс", contentW - 20f - 150f - 52f),
                    Column("Сведений", 52f, Align.RIGHT)
                ),
                rows = used.mapIndexed { i, src ->
                    val count = data.facts.count { src.id in it.sourceIds }
                    listOf((i + 1).toString(), src.name, src.url.ifBlank { "—" }, count.toString())
                }
            )
        }

        gap(14f)
        label("СТРУКТУРА ДОКУМЕНТА")
        bullet("Раздел 0 — введение и методология сбора данных.")
        bullet("Раздел 1 — сводные данные о субъекте.")
        bullet("Раздел 2 — цифровые следы: Telegram, социальные сети, электронная почта, утечки, связи и доказательная база.")
        bullet("Раздел 3 — документы и персональные данные.")
        bullet("Раздел 4 — медиа и изображения.")
        bullet("Раздел 5 — аналитика и выводы: заключение, достоверность, ограничения.")
        bullet("Приложения A–C — словарь терминов, полный список источников со ссылками, сведения о документе.")
    }

    /** Модули, давшие наблюдения: явный список либо выведенный из фактов. */
    private fun sources(): List<ru.jbosint.osint.core.model.SourceRef> =
        data.sources.ifEmpty { SourceCatalog.forFacts(data.facts) }

    // ------------------------------------------------------------ методология

    private fun methodology() {
        contentPage("Методология сбора")
        h1("0.1. Методология сбора данных")
        p("Настоящий отчёт подготовлен по результатам автоматизированного сбора сведений из открытых " +
            "источников (OSINT) и последующей каскадной верификации полученных данных. Обработка выполнена " +
            "в четыре этапа, каждый из которых зафиксирован в структуре документа.")

        h2("0.1.1. Сбор и нормализация")
        p("Запрос «${data.query}» был распределён между всеми активными модулями сбора. Каждый модуль вернул " +
            "структурированный набор наблюдений; значения приведены к единому виду: унифицированы регистр, " +
            "пробельные символы, форматы телефонных номеров, URL, доменных имён и почтовых адресов. " +
            "Служебные, пустые и дублирующиеся в пределах одного модуля поля отброшены. " +
            "Всего обработано ${data.stats.rawFacts} наблюдений от ${data.stats.sourcesResponded} модулей.")

        h2("0.1.2. Триангуляция данных")
        p("Каждое сведение сопоставлено с наблюдениями остальных модулей по нормализованному значению. " +
            "Совпадение в трёх и более независимых источниках трактуется как подтверждённое (триангуляция), " +
            "в двух — как вероятное, в одном — как требующее дополнительной проверки. Такой подход снижает " +
            "риск ошибки отдельного источника и исключает зависимость вывода от единственного канала получения данных.")

        h2("0.1.3. Перекрёстная проверка (cross-referencing)")
        p("Проведено сопоставление атрибутов между объектами исследования. Выявлены пересечения, при которых " +
            "один и тот же идентификатор — адрес электронной почты, номер, домен, никнейм или иное значение — " +
            "встречается у нескольких субъектов. Такие пересечения формируют связи, визуализированные и " +
            "перечисленные в разделе 3. Пересечение, обнаруженное в двух и более источниках, рассматривается " +
            "как устойчивая связь.")

        h2("0.1.4. Анализ метаданных")
        p("Для технических артефактов проанализированы метаданные: регистрационные сведения доменов, " +
            "DNS-записи, сетевая принадлежность IP-адресов, а также встроенные метаданные изображений " +
            "(модель устройства, временные метки, координаты съёмки). Метаданные использованы как " +
            "вспомогательный признак: они указывают направление проверки, но не рассматриваются как " +
            "самостоятельное доказательство.")

        h2("0.1.5. Статистика сессии")
        statTable()
        gap(14f)
        categoryChart()
    }

    private fun statTable() {
        val st = data.stats
        val rows = listOf(
            listOf("Модулей сбора задействовано", st.sourcesAttempted.toString()),
            listOf("Ответили корректно", "${st.sourcesResponded} (${st.successRate}%)"),
            listOf("Недоступны / отказ", st.sourcesFailed.toString()),
            listOf("Наблюдений получено", st.rawFacts.toString()),
            listOf("Уникальных сведений после дедупликации", data.facts.size.toString()),
            listOf("Устранено дубликатов", st.duplicatesRemoved.toString()),
            listOf("Связей построено", data.links.size.toString()),
            listOf("Длительность обработки", "${String.format(Locale.US, "%.1f", st.durationMs / 1000f)} с")
        )
        table(
            columns = listOf(
                Column("Показатель сессии", contentW - 150f),
                Column("Значение", 150f, Align.RIGHT)
            ),
            rows = rows
        )
    }

    private fun categoryChart() {
        val counts = FactCategory.entries.map { it to (data.categoryCounts[it] ?: 0) }
        val max = counts.maxOfOrNull { it.second } ?: 0
        if (max == 0) return
        ensure(30f + counts.size * 20f)
        label("РАСПРЕДЕЛЕНИЕ СВЕДЕНИЙ ПО КАТЕГОРИЯМ")
        gap(8f)
        val barX = marginL + 130f
        val barW = contentW - 130f - 34f
        for ((category, count) in counts) {
            val top = y
            text(marginL, top + 5f, 7.4f, fReg, category.short, Palette.muted)
            page.roundRect(barX, pageH - top - 9f, barW, 9f, 2f, fill = Palette.zebra)
            val w = if (max == 0) 0f else barW * count / max
            if (w > 0.5f) page.roundRect(barX, pageH - top - 9f, w, 9f, 2f, fill = Palette.accent)
            text(barX + barW + 8f, top + 5.6f, 7.4f, fSemi, count.toString(), Palette.ink)
            y += 18f
        }
    }


    // ------------------------------------------------------- сводная информация

    private fun summarySection() {
        newContentPage("Сводные данные")
        h1("1. Сводные данные о субъекте")
        p("Раздел содержит профиль объекта исследования: ключевые атрибуты с указанием источника " +
            "подтверждения, распределение объектов и общую картину подтверждённости сведений. " +
            "Он предназначен для быстрого ознакомления; полная доказательная база с ссылками на каждый " +
            "факт приведена в разделе 2.6 и приложении B.")
        if (meta.photoNote.isNotBlank()) {
            gap(4f)
            callout(meta.photoNote)
        }

        if (data.facts.isEmpty()) {
            callout("Сводка недоступна: по запросу «${data.query}» не получено сведений. Проверьте активность " +
                "модулей сбора и повторите поиск.")
            return
        }

        val order = listOf("Полное имя", "Никнейм", "Почта", "Телефон", "Организация", "IP-адрес", "Домен", "Ссылка")
        val keyFacts = order.mapNotNull { kind ->
            data.facts.filter { it.kind.equals(kind, ignoreCase = true) }
                .maxByOrNull { it.sourceCount }
        }.take(8)

        label("КЛЮЧЕВЫЕ АТРИБУТЫ")
        gap(4f)
        table(
            columns = listOf(
                Column("Атрибут", 84f),
                Column("Значение", 150f),
                Column("Источник подтверждения", contentW - 84f - 150f - 68f),
                Column("Достоверность", 68f)
            ),
            rows = keyFacts.map {
                listOf(
                    it.kind,
                    it.value,
                    ReportSections.sourceNames(it).ifBlank { "Поиск JB OSINT" },
                    "● ${it.confidence.title}"
                )
            },
            cellColors = { row, col -> if (col == 3) Palette.forConfidence(keyFacts[row].confidence) else null }
        )

        gap(14f)
        val confirmed = data.facts.count { it.confidence == Confidence.CONFIRMED }
        val unverified = data.facts.count { it.confidence == Confidence.UNVERIFIED }
        h2("1.1. Общая картина")
        table(
            columns = listOf(
                Column("Показатель", contentW - 90f),
                Column("Значение", 90f, Align.RIGHT)
            ),
            rows = listOf(
                listOf("Объектов (субъектов) в выборке", data.subjects.size.toString()),
                listOf("Установленных сведений", data.facts.size.toString()),
                listOf("Подтверждено независимо", confirmed.toString()),
                listOf("Требует проверки", unverified.toString()),
                listOf("Связей между объектами", data.links.size.toString())
            )
        )

        if (data.subjects.isNotEmpty()) {
            gap(12f)
            h2("1.2. Объекты исследования")
            data.subjects.take(8).forEach { subject ->
                val count = data.facts.count { it.subject == subject }
                bullet("${subject.take(70)} — ${count} сведений")
            }
        }
    }

    // ------------------------------------------------- социальные сети и почта

    private fun socialSection() {
        newContentPage("Социальные сети")
        h1("2.2. Социальные сети и публичные профили")
        p("Раздел объединяет найденные профили в социальных сетях и на публичных платформах. " +
            "Для каждой записи указан модуль, обнаруживший ссылку, и адрес страницы; " +
            "совпадение ссылки в нескольких модулях повышает достоверность.")

        val social = ReportSections.socialFacts(data)
        if (social.isEmpty()) {
            gap(4f)
            callout("Публичные профили в социальных сетях по запросу не обнаружены. Покрытие зависит от " +
                "активных модулей сбора.")
            return
        }

        label("НАЙДЕННЫЕ ПРОФИЛИ (${social.size})")
        gap(4f)
        table(
            columns = listOf(
                Column("№", 20f),
                Column("Платформа", 72f),
                Column("Профиль / ссылка", contentW - 20f - 72f - 104f - 58f),
                Column("Источник", 104f),
                Column("Статус", 58f)
            ),
            rows = social.take(60).mapIndexed { i, f ->
                listOf(
                    (i + 1).toString(),
                    platformOf(f.value),
                    f.value.take(88),
                    ReportSections.sourceNames(f).ifBlank { "—" },
                    "● ${f.confidence.title}"
                )
            },
            cellColors = { row, col ->
                if (col == 4) Palette.forConfidence(social[row].confidence) else null
            }
        )

        gap(12f)
        h2("2.2.1. Наблюдения")
        bullet("Профилей в выборке: ${social.size}; платформ: ${social.map { platformOf(it.value) }.distinct().size}.")
        bullet("Ссылки приведены в том виде, в котором их вернули модули сбора; доступность страниц " +
            "на момент чтения отчёта не гарантируется.")
        bullet("Для профилей с одним подтверждением рекомендуется ручная проверка: никнейм мог быть " +
            "переиспользован другим лицом.")
    }

    private fun platformOf(value: String): String {
        val v = value.lowercase()
        return when {
            "vk.com" in v || "vk.ru" in v -> "VK"
            "ok.ru" in v -> "OK"
            "instagram.com" in v -> "Instagram"
            "facebook.com" in v -> "Facebook"
            "twitter.com" in v || "//x.com" in v -> "X"
            "tiktok.com" in v -> "TikTok"
            "youtube.com" in v -> "YouTube"
            "github.com" in v -> "GitHub"
            "gitlab.com" in v -> "GitLab"
            "linkedin.com" in v -> "LinkedIn"
            "habr.com" in v -> "Habr"
            "t.me" in v || "telegram" in v -> "Telegram"
            else -> "Профиль"
        }
    }

    private fun emailSection() {
        newContentPage("Электронная почта")
        h1("2.3. Адреса электронной почты")
        p("Раздел содержит адреса электронной почты, связанные с объектом, и сведения об их участии " +
            "в публичных утечках. Для каждого адреса указан источник и ссылка на страницу проверки.")

        val emails = ReportSections.emailFacts(data)
        if (emails.isEmpty()) {
            gap(4f)
            callout("Адреса электронной почты по запросу не обнаружены. Если запрос уже является адресом, " +
                "проверьте, что модули поиска утечек активны.")
            return
        }

        label("НАЙДЕННЫЕ АДРЕСА (${emails.size})")
        gap(4f)
        table(
            columns = listOf(
                Column("№", 20f),
                Column("Адрес", contentW - 20f - 118f - 104f - 58f),
                Column("Источник", 104f),
                Column("Утечки", 58f, Align.RIGHT)
            ),
            rows = emails.take(60).mapIndexed { i, f ->
                val leakCount = f.attributes["Записей"]?.takeIf { it.isNotBlank() }
                    ?: if (ReportSections.leakFacts(data).any { it.value == f.value }) "есть" else "—"
                listOf((i + 1).toString(), f.value, ReportSections.sourceNames(f).ifBlank { "—" }, leakCount)
            }
        )

        gap(12f)
        h2("2.3.1. Ссылки на проверку")
        emails.take(30).forEach { f ->
            val url = ReportSections.evidenceUrl(f)
            bullet("${f.value} — ${ReportSections.sourceNames(f).ifBlank { "Поиск JB OSINT" }}" +
                if (url.isBlank()) "" else " · $url")
        }
    }

    // -------------------------------------------------------------- утечки

    private fun leakFacts(): List<Fact> = data.facts.filter {
        val kind = it.kind.lowercase()
        kind.contains("утеч") || kind.contains("leak") || kind.contains("breach") ||
            kind.contains("пароль") || kind.contains("компром")
    }

    private fun leaksSection() {
        newContentPage("Утечки данных")
        h1("2.4. Утечки данных и компрометация")
        p("Раздел обобщает сведения о попадании объекта в публичные базы утечек. Отсутствие записей не " +
            "гарантирует отсутствие компрометации: учитываются только те базы, к которым был доступ у " +
            "подключённых модулей сбора.")

        val leaks = leakFacts()
        if (leaks.isEmpty()) {
            gap(4f)
            callout("Следы компрометации в доступных базах не обнаружены. Рекомендуется проверить, что модули " +
                "поиска утечек активны, и повторить запрос позднее.")
            return
        }

        val records = leaks.sumOf { it.attributes["Записей"]?.toLongOrNull() ?: 0L }
        val confirmed = leaks.count { it.confidence != Confidence.UNVERIFIED }
        val dataClasses = leaks.mapNotNull { it.attributes["Данные"]?.takeIf { d -> d.isNotBlank() } }
            .flatMap { it.split(',', ';').map { part -> part.trim() } }
            .filter { it.isNotEmpty() }
            .distinct()

        label("НАЙДЕННЫЕ ИНЦИДЕНТЫ (${leaks.size})")
        gap(4f)
        table(
            columns = listOf(
                Column("№", 20f),
                Column("Источник утечки", 128f),
                Column("Дата", 56f),
                Column("Домен", contentW - 20f - 128f - 56f - 92f - 52f),
                Column("Скомпрометировано", 92f),
                Column("Записей", 52f, Align.RIGHT)
            ),
            rows = leaks.take(40).mapIndexed { i, f ->
                listOf(
                    (i + 1).toString(),
                    f.value.take(70),
                    f.attributes["Дата"].orEmpty().ifBlank { "—" },
                    f.attributes["Домен"].orEmpty().ifBlank { "—" },
                    f.attributes["Данные"].orEmpty().ifBlank { "—" }.take(48),
                    f.attributes["Записей"].orEmpty().ifBlank { "—" }
                )
            }
        )

        gap(14f)
        h2("2.4.1. Оценка риска")
        val level = when {
            confirmed >= 2 || leaks.size >= 3 -> "ВЫСОКИЙ"
            confirmed == 1 || leaks.size == 2 -> "СРЕДНИЙ"
            else -> "НИЗКИЙ"
        }
        callout("Уровень риска: $level. Инцидентов в доступных базах: ${leaks.size}, из них подтверждено " +
            "независимо: $confirmed." +
            if (records > 0) " Суммарный объём затронутых записей: $records." else "")
        bullet("Затронутые категории данных: " +
            (if (dataClasses.isEmpty()) "не конкретизированы в ответах модулей."
            else dataClasses.take(10).joinToString(", ") + "."))
        bullet("Подтверждение в двух и более наблюдениях повышает достоверность инцидента; единичное " +
            "упоминание следует перепроверить по первичному источнику.")

        gap(8f)
        h2("2.4.2. Рекомендации по снижению риска")
        bullet("Сменить пароли всех учётных записей, связанных с найденными адресами, и включить " +
            "двухфакторную аутентификацию.")
        bullet("Проверить активные сессии и подключённые приложения в почтовых и облачных сервисах.")
        bullet("Включить мониторинг новых утечек по затронутым идентификаторам.")
    }

    // ------------------------------------------------------------ telegram

    private fun telegramFacts(): List<Fact> = data.facts.filter { f ->
        val kind = f.kind.lowercase()
        kind.contains("telegram") ||
            f.value.startsWith("@") ||
            f.value.contains("t.me/") ||
            f.attributes.any { (k, v) ->
                k.equals("Ссылка", ignoreCase = true) && v.lowercase().contains("t.me/")
            }
    }

    private fun telegramSection() {
        newContentPage("Telegram")
        h1("2.1. Telegram-профили и каналы")
        p("Раздел объединяет сведения о присутствии объекта в Telegram: каналы, боты и публичные профили, " +
            "а также связанные с ними метрики. Данные приведены в том виде, в котором их вернули модули сбора.")

        val tg = telegramFacts()
        if (tg.isEmpty()) {
            gap(4f)
            callout("Профили и каналы Telegram по запросу не обнаружены. Покрытие зависит от активных модулей " +
                "сбора; при необходимости подключите дополнительные источники и повторите поиск.")
            return
        }

        val audience = tg.sumOf { it.attributes["Подписчиков"]?.toLongOrNull() ?: 0L }
        val usernames = tg.mapNotNull { f ->
            f.attributes.entries.firstOrNull { it.key.equals("Ссылка", ignoreCase = true) }?.value
                ?: f.value.takeIf { it.startsWith("@") }
        }.distinct()

        label("НАЙДЕННЫЕ ОБЪЕКТЫ (${tg.size})")
        gap(4f)
        table(
            columns = listOf(
                Column("№", 20f),
                Column("Объект", 150f),
                Column("Аудитория", 58f, Align.RIGHT),
                Column("Ссылка", 108f),
                Column("Описание", contentW - 20f - 150f - 58f - 108f)
            ),
            rows = tg.take(40).mapIndexed { i, f ->
                listOf(
                    (i + 1).toString(),
                    f.value.take(64),
                    f.attributes["Подписчиков"].orEmpty().ifBlank { "—" },
                    f.attributes["Ссылка"].orEmpty().ifBlank { "—" },
                    f.attributes["Описание"].orEmpty().ifBlank { "—" }.take(64)
                )
            }
        )

        gap(14f)
        h2("2.1.1. Наблюдения")
        bullet("Объектов Telegram в выборке: ${tg.size}; публичных ссылок: ${usernames.size}.")
        if (audience > 0) {
            bullet("Суммарная заявленная аудитория: $audience подписчиков (по данным модулей сбора).")
        }
        if (usernames.isNotEmpty()) {
            bullet("Идентификаторы: " + usernames.take(12).joinToString(", ") + ".")
        }
        bullet("Метрики аудитории приведены на момент сбора и могут отличаться от текущих значений; " +
            "для каналов учитывается последнее опубликованное значение.")
    }

    // ------------------------------------------------------- профиль связей

    private fun linksSection() {
        newContentPage("Связи")
        h1("2.5. Связи и пересечения")
        p("Раздел отражает структуру отношений между установленными объектами. Узел схемы — объект " +
            "(субъект или значение), ребро — подтверждённое совпадение либо принадлежность атрибута. " +
            "Толщина ребра соответствует числу наблюдений, насыщенность цвета — числу независимых источников, " +
            "в которых связь подтверждена.")

        if (data.links.isEmpty()) {
            gap(6f)
            callout("Связи не построены: в выборке отсутствуют объекты с общими или производными атрибутами. " +
                "Рекомендуется расширить запрос или подключить дополнительные модули сбора.")
        } else {
            gap(4f)
            graphPanel()
            gap(12f)
            h2("2.5.1. Ключевые связи")
            val topLinks = data.links.take(14)
            table(
                columns = listOf(
                    Column("№", 24f),
                    Column("Объект A", 130f),
                    Column("Объект B", 130f),
                    Column("Тип связи", contentW - 24f - 130f - 130f - 96f - 52f),
                    Column("Подтв.", 52f, Align.RIGHT),
                    Column("Наблюд.", 44f, Align.RIGHT)
                ),
                rows = topLinks.mapIndexed { i, l ->
                    listOf(
                        (i + 1).toString(),
                        l.from,
                        l.to,
                        l.relation.replaceFirstChar { it.uppercase() },
                        l.sourceCount.toString(),
                        l.weight.toString()
                    )
                }
            )
            gap(12f)
            matrixPanel()
        }
    }

    private fun graphPanel() {
        val h = 300f
        ensure(h + 16f)
        val top = y
        page.roundRect(marginL, pageH - top - h, contentW, h, 10f, fill = Palette.zebra, strokeColor = Palette.line, strokeWidth = 0.6f)

        val subjects = buildList {
            addAll(data.subjects)
            data.links.forEach { l ->
                if (l.from !in this) add(l.from)
                if (l.to !in this) add(l.to)
            }
        }.distinct()

        val nodes = subjects.take(11)
        if (nodes.size < 2) {
            text(marginL + 20f, top + 40f, 8f, fReg, "Недостаточно объектов для построения схемы связей.", Palette.muted)
            y = top + h + 10f
            return
        }

        val cx = marginL + contentW / 2f
        val cy = top + h / 2f - 6f
        val radius = min(contentW / 2f - 62f, h / 2f - 58f)
        val positions = HashMap<String, Pair<Float, Float>>()
        nodes.forEachIndexed { i, name ->
            val angle = Math.toRadians(-90.0 + 360.0 * i / nodes.size)
            // Координаты в системе "сверху вниз".
            positions[name] = Pair(
                cx + (radius * cos(angle)).toFloat(),
                cy + (radius * sin(angle)).toFloat()
            )
        }

        // Рёбра.
        page.save()
        for (link in data.links) {
            val a = positions[link.from] ?: continue
            val b = positions[link.to] ?: continue
            val width = (0.5f + min(link.weight, 6) * 0.35f)
            page.alpha(if (link.sourceCount >= 2) 0.45f else 0.25f)
            page.line(a.first, pageH - a.second, b.first, pageH - b.second,
                if (link.sourceCount >= 2) Palette.accent else Palette.slate, width)
        }
        page.restore()

        // Узлы.
        for (name in nodes) {
            val (nx, ny) = positions.getValue(name)
            val facts = data.facts.filter { it.subject == name }
            val category = facts.groupingBy { it.category }.eachCount().maxByOrNull { it.value }?.key
            val color = when (category) {
                FactCategory.PERSON -> Palette.accent
                FactCategory.CONTACT -> Palette.accent2
                FactCategory.ORGANIZATION -> Palette.amber
                FactCategory.LOCATION -> Palette.green
                FactCategory.TECHNICAL -> Palette.slate
                FactCategory.DOCUMENT -> Rgb.hex(0x0E7490)
                else -> Palette.muted
            }
            page.circle(nx, pageH - ny, 7.5f, fill = Palette.paper, strokeColor = color, strokeWidth = 1.4f)
            page.circle(nx, pageH - ny, 3.2f, fill = color)
            val label = page.ellipsize(name, fSemi, 6.6f, 96f)
            page.text(nx - fSemi.width(label, 6.6f) / 2f, pageH - ny - 14f, 6.6f, fSemi, label, Palette.ink)
        }

        // Легенда.
        page.save()
        var lx = marginL + 16f
        val ly = top + h - 16f
        for ((category, color) in listOf(
            FactCategory.PERSON to Palette.accent,
            FactCategory.CONTACT to Palette.accent2,
            FactCategory.ORGANIZATION to Palette.amber,
            FactCategory.LOCATION to Palette.green,
            FactCategory.TECHNICAL to Palette.slate
        )) {
            page.circle(lx + 3f, pageH - ly + 2f, 3f, fill = color)
            page.text(lx + 10f, pageH - ly + 4.4f, 6.4f, fReg, category.title, Palette.muted)
            lx += fReg.width(category.title, 6.4f) + 30f
        }
        page.restore()

        y = top + h + 14f
    }

    private fun matrixPanel() {
        val subjects = data.subjects.take(6)
        if (subjects.size < 2) return
        ensure(60f + subjects.size * 18f)
        h2("2.5.2. Матрица пересечений")
        p("Ячейка матрицы показывает число связей между объектами. Заполненные ячейки — подтверждённые " +
            "пересечения, пустые — отсутствие связи в текущей выборке.", leading = 12.4f)

        val labelW = 96f
        val cellW = (contentW - labelW) / subjects.size
        val top = y
        for (i in subjects.indices) {
            page.text(marginL + labelW + i * cellW + 2f, pageH - top - 4f, 6.2f, fSemi,
                page.ellipsize(subjects[i], fSemi, 6.2f, cellW - 4f), Palette.muted)
        }
        y += 12f
        for (i in subjects.indices) {
            val rowTop = y
            page.text(marginL, pageH - rowTop - 4.4f, 6.6f, fSemi,
                page.ellipsize(subjects[i], fSemi, 6.6f, labelW - 8f), Palette.ink)
            for (j in subjects.indices) {
                val x = marginL + labelW + j * cellW
                val count = if (i == j) 0 else data.links.count {
                    (it.from == subjects[i] && it.to == subjects[j]) ||
                        (it.from == subjects[j] && it.to == subjects[i])
                }
                val fill = when {
                    count == 0 -> Palette.zebra
                    count == 1 -> Rgb.hex(0xD9DDFF)
                    count <= 3 -> Rgb.hex(0xA9B0FF)
                    else -> Palette.accent
                }
                page.roundRect(x, pageH - rowTop - 12f, cellW - 4f, 14f, 2.4f, fill = fill)
                if (count > 0) {
                    val label = count.toString()
                    page.text(x + (cellW - 4f - fSemi.width(label, 6.6f)) / 2f, pageH - rowTop - 8.4f, 6.6f,
                        fSemi, label, if (count > 3) Palette.paper else Palette.ink)
                }
            }
            y += 17f
        }
        gap(6f)
    }

    // ------------------------------------------------------ доказательная база

    private fun dossierSection() {
        newContentPage("Доказательная база")
        h1("2.6. Доказательная база")
        p("Раздел содержит все установленные сведения, сгруппированные по категориям. Каждый факт " +
            "оформлен блоком досье: значение, обнаруженный источник со ссылкой на страницу, дата " +
            "обнаружения, статус достоверности и примечание. Дубликаты устранены на этапе нормализации; " +
            "слияние выполнялось по нормализованному значению и субъекту.")

        val ordered = FactCategory.entries.filter { (data.categoryCounts[it] ?: 0) > 0 }
        if (ordered.isEmpty()) {
            gap(4f)
            callout("Доказательная база пуста: по запросу не получено пригодных сведений. Проверьте " +
                "активность модулей сбора или измените формулировку запроса.")
            return
        }

        var budget = 160
        ordered.forEachIndexed { index, category ->
            val facts = data.factsOf(category)
            gap(10f)
            h2("2.6.${index + 1}. ${category.title} · ${facts.size}")
            dossierBlocks(facts, budget)
            budget = (budget - facts.size).coerceAtLeast(0)
            if (budget == 0) {
                bullet("Дальнейшие блоки досье сокращены: полный перечень сведений приведён в " +
                    "XLSX-выгрузке отчёта.")
                return
            }
        }
        factsTable(ordered)
    }

    /** Компактная таблица-указатель для быстрой навигации по разделам 2.6.N. */
    private fun factsTable(ordered: List<FactCategory>) {
        gap(14f)
        h2("2.6.${ordered.size + 1}. Сводная таблица сведений")
        val rows = ArrayList<List<String>>()
        val colors = ArrayList<Rgb>()
        var n = 0
        ordered.forEach { category ->
            data.factsOf(category).take(220).forEach { f ->
                n += 1
                rows += listOf(
                    n.toString(), category.short, f.kind, f.value.take(48),
                    f.sourceCount.toString(), "● ${f.confidence.title}"
                )
                colors += Palette.forConfidence(f.confidence)
            }
        }
        table(
            columns = listOf(
                Column("№", 20f),
                Column("Категория", 58f),
                Column("Сведение", 78f),
                Column("Значение", contentW - 20f - 58f - 78f - 26f - 74f),
                Column("Ист.", 26f, Align.RIGHT),
                Column("Достоверность", 74f)
            ),
            rows = rows,
            cellColors = { row, col -> if (col == 5) colors[row] else null }
        )
    }

    /** Блок досье: значение, источник со ссылкой, дата, статус, примечание. */
    private fun dossierBlocks(facts: List<Fact>, budget: Int) {
        val size = 8.7f
        val leading = 12.6f
        facts.take(budget).forEach { fact ->
            val source = "Источник: " + ReportSections.sourceLine(fact)
            val status = "Дата обнаружения: ${ReportSections.dateText(fact.firstSeen)} · Статус: " +
                ReportSections.statusLine(fact, data)
            val note = ReportSections.noteFor(fact, meta).let { if (it.isBlank()) "" else "Примечание: $it" }
            val lines = wrap(source, fReg, 8.1f, contentW - 26f).size +
                wrap(status, fReg, 8.1f, contentW - 26f).size +
                (if (note.isBlank()) 0 else wrap(note, fReg, 8.1f, contentW - 26f).size)
            val blockH = 20f + lines * leading + 8f
            ensure(blockH + 6f)
            val top = y
            page.roundRect(marginL, pageH - top - blockH, contentW, blockH, 4f, fill = Palette.zebra)
            page.rect(marginL, pageH - top - blockH, 2.2f, blockH, Palette.forConfidence(fact.confidence))
            page.text(marginL + 10f, pageH - top - 12f, size, fSemi, fact.kind, Palette.muted, 0.4f)
            val kindW = fSemi.width(fact.kind, size)
            page.text(marginL + 20f + kindW, pageH - top - 12.4f, size + 0.6f, fBold, fact.value.take(150), Palette.ink)
            var ty = top + 24f
            page.text(marginL + 10f, pageH - ty - fReg.font.ascentPx(8.1f), 8.1f, fReg, source.take(240), Palette.body)
            ty += leading * wrap(source, fReg, 8.1f, contentW - 26f).size
            page.text(marginL + 10f, pageH - ty - fReg.font.ascentPx(8.1f), 8.1f, fReg, status.take(240), Palette.body)
            ty += leading * wrap(status, fReg, 8.1f, contentW - 26f).size
            if (note.isNotBlank()) {
                page.text(marginL + 10f, pageH - ty - fReg.font.ascentPx(8.1f), 8.1f, fReg, note.take(240), Palette.muted)
            }
            y = top + blockH + 7f
        }
    }

    // ------------------------------------------------------------- документы

    private fun documentsSection() {
        newContentPage("Документы")
        h1("3. Документы и персональные данные")
        p("Раздел объединяет сведения документального характера: записи о регистрации, доменные и " +
            "регистрационные данные, упоминания в документах и персональные атрибуты, не отнесённые к " +
            "цифровым следам. Каждая строка сопровождается источником и ссылкой на страницу.")

        val docs = ReportSections.documentFacts(data)
        if (docs.isEmpty()) {
            gap(4f)
            callout("Документальные сведения по запросу не обнаружены. Отсутствие записей не означает их " +
                "отсутствия в реестрах: часть источников могла быть недоступна.")
            return
        }

        label("ДОКУМЕНТАЛЬНЫЕ СВЕДЕНИЯ (${docs.size})")
        gap(4f)
        table(
            columns = listOf(
                Column("№", 20f),
                Column("Документ", 96f),
                Column("Значение", contentW - 20f - 96f - 112f - 64f),
                Column("Источник", 112f),
                Column("Статус", 64f)
            ),
            rows = docs.take(80).mapIndexed { i, f ->
                listOf(
                    (i + 1).toString(), f.kind, f.value.take(70),
                    ReportSections.sourceNames(f).ifBlank { "—" }, "● ${f.confidence.title}"
                )
            },
            cellColors = { row, col -> if (col == 4) Palette.forConfidence(docs[row].confidence) else null }
        )

        gap(12f)
        h2("3.1. Ссылки на документы")
        docs.take(40).forEach { f ->
            val url = ReportSections.evidenceUrl(f)
            bullet("${f.kind}: ${f.value.take(70)}" + if (url.isBlank()) "" else " · $url")
        }

        gap(8f)
        callout("Персональные данные в отчёте приведены в объёме, доступном в открытых источниках. " +
            "Обработка таких данных должна соответствовать применимому законодательству.")
    }

    // ------------------------------------------------------------------ медиа

    private fun mediaSection() {
        newContentPage("Медиа")
        h1("4. Медиа и изображения")
        p("Раздел содержит найденные изображения и медиафайлы, связанные с объектом: аватары профилей, " +
            "публикации и вложения. Для каждого элемента указан источник и адрес, а также отметка о том, " +
            "сопровождался ли он метаданными.")

        if (meta.photoNote.isNotBlank()) {
            gap(4f)
            callout(meta.photoNote)
            gap(6f)
        }

        val media = ReportSections.mediaFacts(data)
        if (media.isEmpty()) {
            gap(4f)
            callout("Медиаматериалы в результатах сбора не зафиксированы. Поиск по изображениям выполняется " +
                "отдельным сценарием: загрузите фотографию в приложении, чтобы получить досье по лицу.")
            return
        }

        label("НАЙДЕННЫЕ МЕДИА (${media.size})")
        gap(4f)
        table(
            columns = listOf(
                Column("№", 20f),
                Column("Тип", 66f),
                Column("Адрес", contentW - 20f - 66f - 108f - 52f),
                Column("Источник", 108f),
                Column("Статус", 52f)
            ),
            rows = media.take(60).mapIndexed { i, f ->
                listOf(
                    (i + 1).toString(),
                    f.kind.take(20),
                    f.value.take(90),
                    ReportSections.sourceNames(f).ifBlank { "—" },
                    "● ${f.confidence.title}"
                )
            },
            cellColors = { row, col -> if (col == 4) Palette.forConfidence(media[row].confidence) else null }
        )

        gap(12f)
        h2("4.1. Метаданные и проверка")
        bullet("Для изображений рекомендуется проверить EXIF-данные: модель устройства, дату съёмки и " +
            "геокоординаты, если они не были удалены при публикации.")
        bullet("Совпадение изображения в нескольких профилях указывает на использование одного аватара " +
            "либо на копирование контента; это не тождественно принадлежности одному лицу.")
        bullet("Ссылки на медиа приведены без встраивания файлов: отчёт не содержит бинарных вложений.")
    }

    // ------------------------------------------------------------- достоверность

    /**
     * Результаты обратного поиска по фотографии: страницы, где встретилось то же
     * лицо или изображение. Раздел отделён от «Медиа», потому что здесь важны
     * схожесть, порог и статус проверки, а не сам файл.
     */
    private fun faceSection() {
        val faces = ReportSections.faceFacts(data)
        if (faces.isEmpty()) return
        newContentPage("Поиск по лицу")
        h1("4.1. Результаты поиска по лицу")
        p("Раздел содержит совпадения, найденные обратным поиском по загруженной фотографии. " +
            "Для каждой находки указаны ресурс, ссылка на страницу, схожесть по шкале сервиса и " +
            "статус проверки. Совпадение изображения указывает на использование того же снимка или " +
            "похожего лица, но само по себе не подтверждает тождество личности.")

        if (meta.photoNote.isNotBlank()) {
            gap(4f)
            callout(meta.photoNote)
            gap(6f)
        }

        label("СОВПАДЕНИЯ ПО ФОТО (${faces.size})")
        gap(4f)
        table(
            columns = listOf(
                Column("№", 20f),
                Column("Страница", contentW - 20f - 60f - 74f - 54f - 96f),
                Column("Схожесть", 60f, Align.RIGHT),
                Column("Статус", 74f),
                Column("Дата", 54f),
                Column("Источник", 96f)
            ),
            rows = faces.take(60).mapIndexed { i, f ->
                listOf(
                    (i + 1).toString(),
                    f.value.take(80),
                    ReportSections.faceSimilarity(f)?.let { "$it%" } ?: "—",
                    "● ${ReportSections.faceStatus(f, data)}",
                    ReportSections.dateText(f.firstSeen),
                    ReportSections.sourceNames(f).ifBlank { "—" }
                )
            }
        )

        gap(10f)
        h2("4.1.1. Карточки находок")
        faces.take(30).forEach { f ->
            val url = ReportSections.evidenceUrl(f)
            bullet("${f.value.take(90)}" + if (url.isBlank()) "" else " · $url")
            bullet("   " + ReportSections.faceNote(f, data))
        }

        gap(10f)
        callout("Порог 90% и независимое подтверждение трактуются как «Подтверждено»; 60–89% — " +
            "«Требует проверки». Перед любыми выводами сверьте профиль вручную: имя, город, " +
            "круг общения и даты публикаций.")
    }

    // ------------------------------------------------------------- достоверность

    private fun confidenceSection() {
        newContentPage("Достоверность")
        h1("5.2. Оценка достоверности (Confidence Level)")
        p("Достоверность каждого факта определяется числом независимых источников, в которых значение " +
            "встретилось после нормализации. Шкала трёхуровневая и применяется единообразно ко всем " +
            "категориям сведений; интерпретация приведена в таблице.")

        val counts = data.confidenceCounts
        val total = data.facts.size.coerceAtLeast(1)
        val rows = Confidence.entries.reversed().map { level ->
            listOf(
                "● ${level.title}",
                level.note,
                (counts[level] ?: 0).toString(),
                "${(counts[level] ?: 0) * 100 / total}%"
            )
        }
        gap(6f)
        table(
            columns = listOf(
                Column("Статус", 110f),
                Column("Критерий присвоения", contentW - 110f - 60f - 52f),
                Column("Фактов", 60f, Align.RIGHT),
                Column("Доля", 52f, Align.RIGHT)
            ),
            rows = rows,
            cellColors = { row, col ->
                if (col == 0) Palette.forConfidence(listOf(Confidence.UNVERIFIED, Confidence.PROBABLE, Confidence.CONFIRMED)[row]) else null
            }
        )

        gap(16f)
        h2("5.2.1. Распределение достоверности")
        gap(4f)
        for (level in Confidence.entries.reversed()) {
            val count = counts[level] ?: 0
            val share = count.toFloat() / total
            val top = y
            text(marginL, top + 5f, 7.6f, fReg, level.title, Palette.body)
            val barX = marginL + 116f
            val barW = contentW - 116f - 40f
            page.roundRect(barX, pageH - top - 9f, barW, 9f, 2f, fill = Palette.zebra)
            if (share > 0f) {
                page.roundRect(barX, pageH - top - 9f, barW * share, 9f, 2f, fill = Palette.forConfidence(level))
            }
            text(barX + barW + 8f, top + 5.6f, 7.2f, fSemi, count.toString(), Palette.ink)
            y += 18f
        }

        gap(14f)
        h2("5.2.2. Правила интерпретации")
        bullet("Подтверждено — сведение воспроизведено в трёх и более независимых наблюдениях, " +
            "расхождений в значении не выявлено; может использоваться как опорный факт.")
        bullet("Вероятно — совпадение в двух наблюдениях; допустимо к использованию с оговоркой " +
            "и указанием числа источников.")
        bullet("Требует проверки — единственное наблюдение; рассматривается как гипотеза, " +
            "требующая независимого подтверждения.")
        bullet("Противоречия фиксируются отдельно: при расхождении значений одного типа у субъекта " +
            "приоритет отдаётся сведению с большим числом подтверждений.")
    }

    // ------------------------------------------------------- ИИ-заключение

    /**
     * Раздел с выводами интеллектуального ассистента. Заголовки модели превращаются
     * в подзаголовки, дефисы — в маркеры списка, служебная разметка Markdown
     * в печатный документ не попадает.
     */
    private fun aiSection() {
        val analysis = meta.aiAnalysis.trim()
        val narrative = meta.aiNarrative.trim()
        if (analysis.isBlank() && narrative.isBlank()) return
        newContentPage("Аналитическое заключение")
        h1("5.1. Аналитическое заключение")
        p("Раздел подготовлен интеллектуальным ассистентом на основе установленных сведений и связей " +
            "из настоящего отчёта. Выводы носят аналитический характер: новых фактов они не добавляют " +
            "и подлежат проверке по первоисточникам.")

        if (analysis.isNotBlank()) {
            gap(10f)
            h2("5.1.1. Выводы по цифровым следам")
            renderAssistantText(analysis)
        }
        if (narrative.isNotBlank()) {
            gap(10f)
            h2("5.1.2. Оформленное резюме исследования")
            renderAssistantText(narrative)
        }
    }

    private fun renderAssistantText(text: String) {
        val lines = text.replace("\r\n", "\n")
            .replace("**", "")
            .replace("__", "")
            .split('\n')
        var inList = false
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isBlank()) {
                if (!inList) gap(4f)
                continue
            }
            when {
                line.startsWith("#") -> {
                    if (inList) gap(4f)
                    inList = false
                    h2(line.trimStart('#').trim().take(90))
                }
                line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ") -> {
                    inList = true
                    bullet(line.substring(2).trim())
                }
                line.matches(Regex("^\\d+[.)]\\s+.*")) -> {
                    inList = true
                    bullet(line.replaceFirst(Regex("^\\d+[.)]\\s+"), ""))
                }
                else -> {
                    if (inList) gap(4f)
                    inList = false
                    p(line)
                }
            }
        }
    }

    // --------------------------------------------------------- ограничения

    private fun limitationsSection() {
        newContentPage("Ограничения")
        h1("5.3. Ограничения и рекомендации")
        h2("5.3.1. Ограничения исследования")
        bullet("Отчёт отражает состояние открытых источников на момент сбора данных; сведения могут " +
            "измениться без уведомления.")
        bullet("Отсутствие сведений в реестре не является доказательством их отсутствия: часть модулей " +
            "могла быть недоступна (см. статистику сессии).")
        bullet("Совпадение значения не всегда означает тождество объектов: возможны одноимённые " +
            "идентификаторы у разных лиц и организаций.")
        bullet("Метаданные технических артефактов подвержены искажению и не рассматриваются как " +
            "самостоятельное доказательство.")

        h2("5.3.2. Рекомендации аналитику")
        bullet("Проверить вручную сведения со статусом «Требует проверки», начиная с объектов с " +
            "наибольшим числом связей.")
        bullet("Для устойчивых пересечений (два и более подтверждения) запросить подтверждающий " +
            "документ или первичный источник.")
        bullet("При подготовке выводов разделять установленные факты и аналитические предположения; " +
            "в тексте ссылаться на номер сведения в реестре.")
        bullet("Повторить сбор через 30 дней для оценки динамики изменений.")

        gap(2f)
        callout("Документ подготовлен автоматизированной системой JB OSINT и предназначен для внутреннего " +
            "использования. Он не является юридическим заключением и не заменяет проверку, выполняемую " +
            "аттестованным специалистом.")
    }

    private fun appendix() {
        newContentPage("Приложение")
        h1("Приложение A. Словарь терминов")
        val glossary = listOf(
            "OSINT" to "Разведка на основе открытых источников информации.",
            "Триангуляция" to "Подтверждение сведения совпадением в трёх и более независимых источниках.",
            "Cross-referencing" to "Перекрёстная проверка: сопоставление атрибутов между объектами.",
            "Confidence Level" to "Оценка достоверности факта по числу независимых подтверждений.",
            "Нормализация" to "Приведение значений к единому виду для корректного сравнения.",
            "Профилирование связей" to "Построение структуры отношений между установленными объектами.",
            "Цифровой след" to "Совокупность данных, оставленных субъектом в открытых источниках, и связей между ними."
        )
        table(
            columns = listOf(Column("Термин", 130f), Column("Определение", contentW - 130f)),
            rows = glossary.map { listOf(it.first, it.second) }
        )

        gap(16f)
        newContentPage("Приложение B")
        h1("Приложение B. Источники и ссылки")
        p("Перечень модулей сбора и адресов, использованных при подготовке досье. Для каждого сведения " +
            "в разделе 2.6 указан конкретный источник; здесь приведены базовые адреса ресурсов и " +
            "собранные ссылки-доказательства.", color = Palette.muted, size = 8.4f)

        gap(4f)
        val refs = sources()
        table(
            columns = listOf(Column("№", 20f), Column("Модуль", 150f), Column("Ресурс", contentW - 170f)),
            rows = refs.mapIndexed { i, src -> listOf((i + 1).toString(), src.name, src.url.ifBlank { "—" }) }
        )

        val links = data.facts.flatMap { fact ->
            fact.urls.map { url -> fact.kind to url }
        }.distinct().take(300)
        if (links.isNotEmpty()) {
            gap(14f)
            h2("Собранные ссылки-доказательства (${links.size})")
            table(
                columns = listOf(
                    Column("№", 20f),
                    Column("Сведение", 86f),
                    Column("Ссылка", contentW - 106f)
                ),
                rows = links.mapIndexed { i, (kind, url) -> listOf((i + 1).toString(), kind, url) }
            )
        }

        gap(16f)
        newContentPage("Приложение C")
        h1("Приложение C. Сведения о документе")
        val info = listOf(
            listOf("Идентификатор отчёта", meta.reportId),
            listOf("Контрольная сумма данных", dataHash()),
            listOf("Аналитик", meta.analyst.ifBlank { "Не указан" }),
            listOf("Дата формирования", dateText(meta.createdAt)),
            listOf("Объект запроса", data.query),
            listOf("Сведений в реестре", data.facts.size.toString()),
            listOf("Связей построено", data.links.size.toString()),
            listOf("Страниц в документе", "—"),
            listOf("Версия системы", "JB OSINT ${meta.appVersion}")
        )
        table(
            columns = listOf(Column("Параметр", 150f), Column("Значение", contentW - 150f)),
            rows = info
        )
        gap(12f)
        p("Контрольная сумма рассчитывается по нормализованному набору сведений и позволяет убедиться, " +
            "что документ не был изменён после формирования.", color = Palette.muted, size = 8.2f)
    }

    // ------------------------------------------------------------------ утилиты

    private fun dataHash(): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(data.query.toByteArray())
        data.facts.sortedBy { it.category.order.toString() + it.value }.forEach {
            md.update((it.kind + it.value + it.subject + it.sourceCount).toByteArray())
        }
        return md.digest().take(8).joinToString("") { String.format(Locale.US, "%02x", it) }.uppercase()
    }

    private fun dateText(ts: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date(if (ts == 0L) System.currentTimeMillis() else ts))

    private fun contentPage(title: String) {
        page = doc.newPage()
        section = title
        y = contentTop
        drawHeader()
    }

    private fun newContentPage(title: String) = contentPage(title)

    private fun drawHeader() {
        page.text(marginL, pageH - 44f, 7.2f, fBold, "JB OSINT", Palette.ink, 1.2f)
        page.circle(marginL + 44f, pageH - 46.4f, 1.8f, fill = Palette.accent)
        val title = section.uppercase()
        page.text(pageW - marginL - fReg.width(title, 6.2f), pageH - 44f, 6.2f, fReg, title, Palette.faint, 1.2f)
        page.line(marginL, pageH - 56f, pageW - marginL, pageH - 56f, Palette.line, 0.6f)
    }

    private fun drawFooters() {
        val total = doc.pages.size
        doc.pages.forEachIndexed { index, p ->
            if (index == 0) return@forEachIndexed
            val baseline = 34f
            p.line(marginL, baseline + 12f, pageW - marginL, baseline + 12f, Palette.line, 0.5f)
            p.text(marginL, baseline, 6.2f, fSemi, "JB OSINT", Palette.muted, 0.8f)
            p.text(marginL + 40f, baseline, 6.2f, fReg, "· ${meta.reportId} · ${meta.classification.lowercase().replaceFirstChar { it.uppercase() }}",
                Palette.faint, 0.4f)
            val pages = "стр. ${index + 1} / $total"
            p.text(pageW - marginL - p.let { fReg.width(pages, 6.2f) }, baseline, 6.2f, fReg, pages, Palette.muted)
        }
    }

    private fun paginate() {
        drawFooters()
    }

    private fun ensure(space: Float) {
        if (y + space > contentBottom) {
            page = doc.newPage()
            y = contentTop
            drawHeader()
        }
    }

    private fun gap(value: Float) {
        y += value
    }

    private fun h1(text: String) {
        ensure(44f)
        y += 6f
        page.rect(marginL, pageH - y - 13f, 3.2f, 15f, Palette.accent)
        page.text(marginL + 12f, pageH - y - 10.4f, 12.4f, fBold, text, Palette.ink)
        y += 24f
    }

    private fun h2(text: String) {
        ensure(24f)
        y += 6f
        page.text(marginL, pageH - y - 8f, 8.8f, fSemi, text, Palette.ink, 0.2f)
        y += 18f
    }

    private fun label(text: String) {
        ensure(14f)
        page.text(marginL, pageH - y - 8f, 6.4f, fSemi, text, Palette.muted, 1.6f)
        y += 14f
    }

    private fun p(
        text: String,
        size: Float = 9.2f,
        leading: Float = 14.4f,
        color: Rgb = Palette.body
    ) {
        val lines = wrap(text, fReg, size, contentW)
        for (line in lines) {
            ensure(leading)
            page.text(marginL, pageH - y - fReg.font.ascentPx(size), size, fReg, line, color)
            y += leading
        }
        y += 4f
    }

    private fun bullet(text: String) {
        val size = 8.8f
        val leading = 13.6f
        val indent = 14f
        val lines = wrap(text, fReg, size, contentW - indent)
        lines.forEachIndexed { index, line ->
            ensure(leading + 2f)
            if (index == 0) {
                page.circle(marginL + 3f, pageH - y - 5.6f, 1.7f, fill = Palette.accent)
            }
            page.text(marginL + indent, pageH - y - fReg.font.ascentPx(size), size, fReg, line, Palette.body)
            y += leading
        }
        y += 3f
    }

    private fun callout(text: String) {
        val size = 8.4f
        val leading = 13f
        val pad = 12f
        val lines = wrap(text, fReg, size, contentW - pad * 2 - 6f)
        val height = lines.size * leading + pad * 2 - 2f
        ensure(height + 8f)
        val top = y
        page.roundRect(marginL, pageH - top - height, contentW, height, 6f, fill = Palette.accentSoft)
        page.rect(marginL, pageH - top - height, 2.6f, height, Palette.accent)
        var ty = top + pad + 1f
        for (line in lines) {
            page.text(marginL + pad + 4f, pageH - ty - fReg.font.ascentPx(size), size, fReg, line, Palette.ink)
            ty += leading
        }
        y = top + height + 10f
    }

    private data class Column(val title: String, val width: Float, val align: Align = Align.LEFT)

    private enum class Align { LEFT, RIGHT }

    private fun table(
        columns: List<Column>,
        rows: List<List<String>>,
        cellColors: ((Int, Int) -> Rgb?)? = null
    ) {
        val headerH = 22f
        val bodySize = 7.5f
        val bodyLeading = 10.2f
        val padX = 5f
        val padY = 5.4f

        fun measureRow(row: List<String>): Pair<Float, List<List<String>>> {
            val wrapped = row.mapIndexed { i, cell ->
                wrap(cell, fReg, bodySize, columns[i].width - padX * 2)
            }
            val lines = wrapped.maxOf { it.size }.coerceAtLeast(1)
            return Pair(lines * bodyLeading + padY * 2, wrapped)
        }

        fun drawHeaderRow() {
            ensure(headerH + 26f)
            page.rect(marginL, pageH - y - headerH, contentW, headerH, Palette.dark)
            var x = marginL
            columns.forEach { col ->
                val title = page.ellipsize(col.title.uppercase(), fSemi, 6.2f, col.width - 8f)
                val tx = if (col.align == Align.RIGHT) x + col.width - 5f - fSemi.width(title, 6.2f) else x + 5f
                page.text(tx, pageH - y - 14.4f, 6.2f, fSemi, title, Palette.onDark, 0.8f)
                x += col.width
            }
            y += headerH
        }

        drawHeaderRow()
        rows.forEachIndexed { rowIndex, row ->
            val (height, wrapped) = measureRow(row)
            if (y + height > contentBottom) {
                page = doc.newPage()
                y = contentTop
                drawHeader()
                drawHeaderRow()
            }
            if (rowIndex % 2 == 1) {
                page.rect(marginL, pageH - y - height, contentW, height, Palette.zebra)
            }
            var x = marginL
            columns.forEachIndexed { colIndex, col ->
                val color = cellColors?.invoke(rowIndex, colIndex) ?: Palette.body
                val lines = wrapped[colIndex]
                var ty = y + padY
                for (line in lines) {
                    val tx = if (col.align == Align.RIGHT) x + col.width - padX - fReg.width(line, bodySize) else x + padX
                    page.text(tx, pageH - ty - fReg.font.ascentPx(bodySize), bodySize, fReg, line, color)
                    ty += bodyLeading
                }
                x += col.width
            }
            page.save()
            page.alpha(0.55f)
            page.line(marginL, pageH - y - height, pageW - marginL, pageH - y - height, Palette.line, 0.5f)
            page.restore()
            y += height
        }
        y += 6f
    }

    private fun text(x: Float, top: Float, size: Float, handle: PdfFontHandle, value: String, color: Rgb, tracking: Float = 0f) {
        page.text(x, pageH - top - handle.font.ascentPx(size), size, handle, value, color, tracking)
    }

    /** Разбиение текста по ширине с переносом по словам. */
    private fun wrap(text: String, handle: PdfFontHandle, size: Float, maxWidth: Float): List<String> {
        val out = ArrayList<String>()
        for (rawLine in text.split('\n')) {
            val words = rawLine.split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty()) {
                out.add("")
                continue
            }
            val current = StringBuilder()
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (handle.width(candidate, size) <= maxWidth) {
                    current.setLength(0)
                    current.append(candidate)
                } else {
                    if (current.isNotEmpty()) out.add(current.toString())
                    // Слово длиннее строки — режем посимвольно.
                    var rest = word
                    while (handle.width(rest, size) > maxWidth && rest.length > 1) {
                        var cut = rest.length
                        while (cut > 1 && handle.width(rest.substring(0, cut), size) > maxWidth) cut--
                        out.add(rest.substring(0, cut))
                        rest = rest.substring(cut)
                    }
                    current.setLength(0)
                    current.append(rest)
                }
            }
            if (current.isNotEmpty()) out.add(current.toString())
        }
        return out
    }
}
