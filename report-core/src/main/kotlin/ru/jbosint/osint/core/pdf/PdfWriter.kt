package ru.jbosint.osint.core.pdf

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.Deflater

/** Цвет в модели RGB (0..1). */
data class Rgb(val r: Float, val g: Float, val b: Float) {
    companion object {
        fun hex(value: Int): Rgb = Rgb(
            ((value shr 16) and 0xFF) / 255f,
            ((value shr 8) and 0xFF) / 255f,
            (value and 0xFF) / 255f
        )
        val WHITE = hex(0xFFFFFF)
        val BLACK = hex(0x000000)
    }
}

internal fun fmt(value: Float): String =
    String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.').ifBlank { "0" }

/** Низкоуровневое хранилище объектов PDF. */
internal class PdfObjectStore {
    private val bodies = ArrayList<ByteArray?>()

    fun reserve(): Int {
        bodies.add(null)
        return bodies.size
    }

    fun set(id: Int, body: ByteArray) {
        bodies[id - 1] = body
    }

    fun setText(id: Int, text: String) = set(id, text.toByteArray(Charsets.ISO_8859_1))

    fun stream(dict: Map<String, String>, data: ByteArray, compress: Boolean = true): Int {
        val payload = if (compress) deflate(data) else data
        val id = reserve()
        val header = StringBuilder("<< ")
        if (compress) header.append("/Filter /FlateDecode ")
        for ((k, v) in dict) header.append('/').append(k).append(' ').append(v).append(' ')
        header.append("/Length ").append(payload.size).append(" >>\nstream\n")
        val out = ByteArrayOutputStream()
        out.write(header.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(payload)
        out.write("\nendstream".toByteArray(Charsets.ISO_8859_1))
        set(id, out.toByteArray())
        return id
    }

    fun build(rootId: Int, infoId: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val header = "%PDF-1.7\n%\u00E2\u00E3\u00CF\u00D3\n"
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        val offsets = IntArray(bodies.size + 1)
        var position = out.size()
        for (i in bodies.indices) {
            val body = bodies[i] ?: error("Объект ${i + 1} не заполнен")
            offsets[i + 1] = position
            val prefix = "${i + 1} 0 obj\n".toByteArray(Charsets.ISO_8859_1)
            out.write(prefix)
            out.write(body)
            val suffix = "\nendobj\n".toByteArray(Charsets.ISO_8859_1)
            out.write(suffix)
            position += prefix.size + body.size + suffix.size
        }
        val xrefPos = position
        val xref = StringBuilder()
        xref.append("xref\n0 ").append(bodies.size + 1).append('\n')
        xref.append("0000000000 65535 f \n")
        for (i in 1..bodies.size) {
            xref.append(String.format(Locale.US, "%010d 00000 n \n", offsets[i]))
        }
        val trailer = "trailer\n<< /Size ${bodies.size + 1} /Root $rootId 0 R /Info $infoId 0 R >>\n" +
            "startxref\n$xrefPos\n%%EOF\n"
        out.write(xref.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(trailer.toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 2 + 64)
        val buffer = ByteArray(16384)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            out.write(buffer, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }
}

/** Зарегистрированный в документе шрифт. */
class PdfFontHandle internal constructor(
    val resourceName: String,
    val font: TtfFont,
    val subsetTag: String
) {
    internal val usedGlyphs = LinkedHashMap<Int, Int>() // gid -> code point

    fun width(text: String, size: Float): Float = font.widthOf(text, size)

    internal fun encode(text: String): String {
        val sb = StringBuilder(text.length * 5)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            var gid = font.glyphId(cp)
            if (gid == 0) {
                // Резервный глиф: заменяем на «?» (gid для U+003F), иначе на пробел.
                gid = font.glyphId('?'.code)
                if (gid == 0) continue
            }
            val unicode = font.let { _ -> cp }
            usedGlyphs[gid] = unicode
            sb.append(String.format(Locale.US, "%04X", gid))
        }
        return sb.toString()
    }
}

/** Страница: накапливает операторы содержимого в координатах PDF (origin — левый нижний угол). */
class PdfPage internal constructor(
    private val doc: PdfDocument,
    val width: Float,
    val height: Float
) {
    internal val ops = StringBuilder(4096)

    fun save() { ops.append("q\n") }
    fun restore() { ops.append("Q\n") }

    fun alpha(value: Float): PdfPage {
        ops.append('/').append(doc.alphaState(value)).append(" gs\n")
        return this
    }

    fun fill(color: Rgb): PdfPage {
        ops.append("${fmt(color.r)} ${fmt(color.g)} ${fmt(color.b)} rg\n")
        return this
    }

    fun stroke(color: Rgb): PdfPage {
        ops.append("${fmt(color.r)} ${fmt(color.g)} ${fmt(color.b)} RG\n")
        return this
    }

    fun lineWidth(width: Float): PdfPage {
        ops.append("${fmt(width)} w\n")
        return this
    }

    fun rect(x: Float, y: Float, w: Float, h: Float, fill: Rgb? = null, strokeColor: Rgb? = null, strokeWidth: Float = 1f) {
        fill?.let { this.fill(it) }
        strokeColor?.let { stroke(it); lineWidth(strokeWidth) }
        ops.append("${fmt(x)} ${fmt(y)} ${fmt(w)} ${fmt(h)} re\n")
        when {
            fill != null && strokeColor != null -> ops.append("B\n")
            fill != null -> ops.append("f\n")
            strokeColor != null -> ops.append("S\n")
        }
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Rgb, width: Float = 1f) {
        stroke(color)
        lineWidth(width)
        ops.append("${fmt(x1)} ${fmt(y1)} m ${fmt(x2)} ${fmt(y2)} l S\n")
    }

    fun roundRect(x: Float, y: Float, w: Float, h: Float, radius: Float, fill: Rgb? = null, strokeColor: Rgb? = null, strokeWidth: Float = 1f) {
        val r = minOf(radius, w / 2f, h / 2f)
        val k = r * 0.5523f
        fill?.let { this.fill(it) }
        strokeColor?.let { stroke(it); lineWidth(strokeWidth) }
        ops.append("${fmt(x + r)} ${fmt(y)} m\n")
        ops.append("${fmt(x + w - r)} ${fmt(y)} l\n")
        ops.append("${fmt(x + w - r + k)} ${fmt(y)} ${fmt(x + w)} ${fmt(y + r - k)} ${fmt(x + w)} ${fmt(y + r)} c\n")
        ops.append("${fmt(x + w)} ${fmt(y + h - r)} l\n")
        ops.append("${fmt(x + w)} ${fmt(y + h - r + k)} ${fmt(x + w - r + k)} ${fmt(y + h)} ${fmt(x + w - r)} ${fmt(y + h)} c\n")
        ops.append("${fmt(x + r)} ${fmt(y + h)} l\n")
        ops.append("${fmt(x + r - k)} ${fmt(y + h)} ${fmt(x)} ${fmt(y + h - r + k)} ${fmt(x)} ${fmt(y + h - r)} c\n")
        ops.append("${fmt(x)} ${fmt(y + r)} l\n")
        ops.append("${fmt(x)} ${fmt(y + r - k)} ${fmt(x + r - k)} ${fmt(y)} ${fmt(x + r)} ${fmt(y)} c\n")
        ops.append("h\n")
        when {
            fill != null && strokeColor != null -> ops.append("B\n")
            fill != null -> ops.append("f\n")
            strokeColor != null -> ops.append("S\n")
        }
    }

    fun circle(cx: Float, cy: Float, r: Float, fill: Rgb? = null, strokeColor: Rgb? = null, strokeWidth: Float = 1f) {
        val k = r * 0.5523f
        fill?.let { this.fill(it) }
        strokeColor?.let { stroke(it); lineWidth(strokeWidth) }
        ops.append("${fmt(cx + r)} ${fmt(cy)} m\n")
        ops.append("${fmt(cx + r)} ${fmt(cy + k)} ${fmt(cx + k)} ${fmt(cy + r)} ${fmt(cx)} ${fmt(cy + r)} c\n")
        ops.append("${fmt(cx - k)} ${fmt(cy + r)} ${fmt(cx - r)} ${fmt(cy + k)} ${fmt(cx - r)} ${fmt(cy)} c\n")
        ops.append("${fmt(cx - r)} ${fmt(cy - k)} ${fmt(cx - k)} ${fmt(cy - r)} ${fmt(cx)} ${fmt(cy - r)} c\n")
        ops.append("${fmt(cx + k)} ${fmt(cy - r)} ${fmt(cx + r)} ${fmt(cy - k)} ${fmt(cx + r)} ${fmt(cy)} c\n")
        when {
            fill != null && strokeColor != null -> ops.append("B\n")
            fill != null -> ops.append("f\n")
            else -> ops.append("S\n")
        }
    }

    fun polygon(points: List<Pair<Float, Float>>, fill: Rgb? = null, strokeColor: Rgb? = null, strokeWidth: Float = 1f) {
        if (points.size < 3) return
        fill?.let { this.fill(it) }
        strokeColor?.let { stroke(it); lineWidth(strokeWidth) }
        points.forEachIndexed { index, point ->
            val op = if (index == 0) "m" else "l"
            ops.append("${fmt(point.first)} ${fmt(point.second)} $op ")
        }
        ops.append("h ")
        when {
            fill != null && strokeColor != null -> ops.append("B\n")
            fill != null -> ops.append("f\n")
            else -> ops.append("S\n")
        }
    }

    /** Многострочный текст с базовой линией в координатах PDF. */
    fun text(x: Float, baselineY: Float, size: Float, handle: PdfFontHandle, text: String, color: Rgb, tracking: Float = 0f) {
        if (text.isEmpty()) return
        val hex = handle.encode(text)
        if (hex.isEmpty()) return
        fill(color)
        ops.append("BT\n")
        ops.append("/").append(handle.resourceName).append(' ').append(fmt(size)).append(" Tf\n")
        if (tracking != 0f) ops.append("${fmt(tracking)} Tc\n")
        ops.append("1 0 0 1 ${fmt(x)} ${fmt(baselineY)} Tm\n")
        ops.append('<').append(hex).append("> Tj\n")
        if (tracking != 0f) ops.append("0 Tc\n")
        ops.append("ET\n")
    }

    /** Текст относительно верхней границы строки (top-down координаты страницы). */
    fun textTop(x: Float, top: Float, size: Float, handle: PdfFontHandle, text: String, color: Rgb, tracking: Float = 0f) {
        val baseline = height - top - handle.font.ascentPx(size)
        text(x, baseline, size, handle, text, color, tracking)
    }

    /** Обрезает строку по ширине, добавляя многоточие. */
    fun ellipsize(text: String, handle: PdfFontHandle, size: Float, maxWidth: Float): String {
        if (handle.width(text, size) <= maxWidth) return text
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            val candidate = text.substring(0, mid) + "…"
            if (handle.width(candidate, size) <= maxWidth) lo = mid else hi = mid - 1
        }
        return if (lo == 0) "" else text.substring(0, lo).trimEnd() + "…"
    }
}

/** PDF-документ: страницы, шрифты, состояния прозрачности. */
class PdfDocument(
    val pageWidth: Float = 595.28f,
    val pageHeight: Float = 841.89f
) {
    private val store = PdfObjectStore()
    internal val pages = ArrayList<PdfPage>()
    private val fonts = LinkedHashMap<String, PdfFontHandle>()
    private val alphaStates = LinkedHashMap<String, Int>()
    private var fontCounter = 0

    fun newPage(): PdfPage = PdfPage(this, pageWidth, pageHeight).also { pages.add(it) }

    fun registerFont(ttf: TtfFont, resourceName: String = "F${++fontCounter}"): PdfFontHandle {
        val tag = subsetTag(ttf.postScriptName)
        val handle = PdfFontHandle(resourceName, ttf, tag)
        fonts[resourceName] = handle
        return handle
    }

    internal fun alphaState(value: Float): String {
        val key = "GS${(value * 100).toInt()}"
        if (key !in alphaStates) {
            val id = store.reserve()
            store.setText(id, "<< /Type /ExtGState /ca ${fmt(value)} /CA ${fmt(value)} >>")
            alphaStates[key] = id
        }
        return key
    }

    fun write(info: Map<String, String>): ByteArray {
        // 1. Шрифты.
        val fontRefs = LinkedHashMap<String, Int>()
        for ((resName, handle) in fonts) {
            fontRefs[resName] = writeFont(handle)
        }

        // 2. Страницы.
        val pagesId = store.reserve()
        val pageIds = ArrayList<Int>()
        for (page in pages) {
            val contentId = store.stream(
                mapOf(),
                page.ops.toString().toByteArray(Charsets.ISO_8859_1),
                compress = true
            )
            val pageId = store.reserve()
            val fontDict = fontRefs.entries.joinToString(" ") { (name, id) -> "/$name $id 0 R" }
            val gsDict = alphaStates.entries.joinToString(" ") { (name, id) -> "/$name $id 0 R" }
            store.setText(
                pageId,
                "<< /Type /Page /Parent $pagesId 0 R /MediaBox [0 0 ${fmt(pageWidth)} ${fmt(pageHeight)}] " +
                    "/Resources << /Font << $fontDict >> /ExtGState << $gsDict >> /ProcSet [/PDF /Text] >> " +
                    "/Contents $contentId 0 R >>"
            )
            pageIds.add(pageId)
        }
        store.setText(
            pagesId,
            "<< /Type /Pages /Count ${pageIds.size} /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] >>"
        )

        // 3. Каталог и информация.
        val catalogId = store.reserve()
        store.setText(catalogId, "<< /Type /Catalog /Pages $pagesId 0 R >>")

        val infoId = store.reserve()
        val infoBody = buildString {
            append("<< ")
            info.forEach { (k, v) ->
                append('/').append(k).append(" <FEFF")
                append(v.toByteArray(Charsets.UTF_16BE).joinToString("") { b -> String.format(Locale.US, "%02X", b) })
                append("> ")
            }
            append(">>")
        }
        store.setText(infoId, infoBody)

        return store.build(catalogId, infoId)
    }

    private fun writeFont(handle: PdfFontHandle): Int {
        val ttf = handle.font
        val baseFont = ttf.subsetName(handle.subsetTag)

        // FontFile2 — сжатый исходный TrueType.
        val fileId = store.stream(
            mapOf("Length1" to ttf.data.size.toString()),
            ttf.data,
            compress = true
        )

        val scale = 1000f / ttf.unitsPerEm
        val widths = handle.usedGlyphs.keys.sorted().joinToString(" ") { gid ->
            "$gid [${(ttf.glyphAdvance(gid) * scale).toInt().coerceAtLeast(1)}]"
        }
        val wArray = if (widths.isEmpty()) "" else "/W [$widths] "

        val descriptorId = store.reserve()
        store.setText(
            descriptorId,
            "<< /Type /FontDescriptor /FontName /$baseFont /Flags 32 " +
                "/FontBBox [${ttf.bbox[0]} ${ttf.bbox[1]} ${ttf.bbox[2]} ${ttf.bbox[3]}] " +
                "/ItalicAngle 0 /Ascent ${ttf.ascent} /Descent ${ttf.descent} " +
                "/CapHeight ${ttf.capHeight} /StemV 80 /FontFile2 $fileId 0 R >>"
        )

        val cidId = store.reserve()
        store.setText(
            cidId,
            "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /$baseFont " +
                "/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> " +
                "$wArray/DW 1000 /FontDescriptor $descriptorId 0 R /CIDToGIDMap /Identity >>"
        )

        val cmapId = store.stream(mapOf(), buildToUnicode(handle), compress = true)

        val type0Id = store.reserve()
        store.setText(
            type0Id,
            "<< /Type /Font /Subtype /Type0 /BaseFont /$baseFont /Encoding /Identity-H " +
                "/DescendantFonts [$cidId 0 R] /ToUnicode $cmapId 0 R >>"
        )
        return type0Id
    }

    private fun buildToUnicode(handle: PdfFontHandle): ByteArray {
        val sb = StringBuilder()
        sb.append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
        sb.append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
        sb.append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
        sb.append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")
        val entries = handle.usedGlyphs.entries.toList()
        entries.chunked(100).forEach { chunk ->
            sb.append("${chunk.size} beginbfchar\n")
            for ((gid, cp) in chunk) {
                sb.append('<').append(String.format(Locale.US, "%04X", gid)).append("> ")
                if (cp > 0xFFFF) {
                    val v = cp - 0x10000
                    val hi = 0xD800 + (v shr 10)
                    val lo = 0xDC00 + (v and 0x3FF)
                    sb.append(String.format(Locale.US, "<%04X%04X>", hi, lo)).append('\n')
                } else {
                    sb.append(String.format(Locale.US, "<%04X>", cp)).append('\n')
                }
            }
            sb.append("endbfchar\n")
        }
        sb.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun subsetTag(name: String): String {
        val hash = name.hashCode() * 31 + pageWidth.toInt()
        var h = hash.toLong() and 0xFFFFFFFFL
        val sb = StringBuilder()
        repeat(6) {
            sb.append(('A' + (h % 26).toInt()))
            h /= 26
        }
        return sb.toString()
    }
}
