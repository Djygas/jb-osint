package ru.jbosint.osint.core.pdf

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Минимальный парсер TrueType: метрики, cmap и идентификатор шрифта.
 * Нужен для точной вёрстки (измерение ширин) и для встраивания шрифта в PDF.
 */
class TtfFont private constructor(
    val data: ByteArray,
    val unitsPerEm: Int,
    val ascent: Int,
    val descent: Int,
    val capHeight: Int,
    val bbox: IntArray,
    private val advances: IntArray,
    private val cmap: Map<Int, Int>,
    val postScriptName: String,
    val numGlyphs: Int
) {

    fun glyphId(codePoint: Int): Int = cmap[codePoint] ?: 0

    fun glyphAdvance(gid: Int): Int = if (gid in advances.indices) advances[gid] else 0

    /** Ширина строки в пунктах при заданном кегле. */
    fun widthOf(text: String, size: Float): Float {
        var units = 0L
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            units += glyphAdvance(glyphId(cp))
            i += Character.charCount(cp)
        }
        return units * size / unitsPerEm
    }

    fun ascentPx(size: Float): Float = ascent.toFloat() * size / unitsPerEm

    fun descentPx(size: Float): Float = descent.toFloat() * size / unitsPerEm

    fun capHeightPx(size: Float): Float = capHeight.toFloat() * size / unitsPerEm

    /** Имя шрифта внутри PDF с префиксом подмножества. */
    fun subsetName(tag: String): String = "$tag+$postScriptName"

    companion object {
        fun load(stream: InputStream, postScriptName: String): TtfFont {
            val bytes = stream.use { it.readBytes() }
            return parse(bytes, postScriptName)
        }

        fun parse(bytes: ByteArray, fallbackName: String): TtfFont {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val numTables = buf.getShort(4).toInt() and 0xFFFF
            val tables = HashMap<String, Pair<Int, Int>>()
            var rec = 12
            repeat(numTables) {
                val tag = String(bytes, rec, 4, Charsets.US_ASCII)
                val offset = buf.getInt(rec + 8)
                val length = buf.getInt(rec + 12)
                tables[tag] = offset to length
                rec += 16
            }

            val head = tables["head"]?.first ?: error("Нет таблицы head")
            val unitsPerEm = buf.getShort(head + 18).toInt() and 0xFFFF
            val xMin = buf.getShort(head + 36).toInt()
            val yMin = buf.getShort(head + 38).toInt()
            val xMax = buf.getShort(head + 40).toInt()
            val yMax = buf.getShort(head + 42).toInt()

            val hhea = tables["hhea"]?.first ?: error("Нет таблицы hhea")
            val ascent = buf.getShort(hhea + 4).toInt()
            val descent = buf.getShort(hhea + 6).toInt()
            val numberOfHMetrics = buf.getShort(hhea + 34).toInt() and 0xFFFF

            val maxp = tables["maxp"]?.first ?: error("Нет таблицы maxp")
            val numGlyphs = buf.getShort(maxp + 4).toInt() and 0xFFFF

            val advances = IntArray(numGlyphs)
            val hmtx = tables["hmtx"]?.first ?: error("Нет таблицы hmtx")
            var last = 0
            for (g in 0 until numGlyphs) {
                if (g < numberOfHMetrics) {
                    last = buf.getShort(hmtx + g * 4).toInt() and 0xFFFF
                }
                advances[g] = last
            }

            var capHeight = (unitsPerEm * 0.72f).toInt()
            tables["OS/2"]?.let { (os2, _) ->
                val version = buf.getShort(os2).toInt() and 0xFFFF
                if (version >= 2) {
                    val ch = buf.getShort(os2 + 88).toInt()
                    if (ch > 0) capHeight = ch
                }
            }

            val cmapTable = tables["cmap"]?.first
            val cmap = if (cmapTable != null) parseCmap(buf, cmapTable, bytes) else emptyMap()

            val psName = readPostScriptName(buf, tables["name"]?.first, bytes) ?: fallbackName

            return TtfFont(
                data = bytes,
                unitsPerEm = if (unitsPerEm == 0) 1000 else unitsPerEm,
                ascent = ascent,
                descent = descent,
                capHeight = capHeight,
                bbox = intArrayOf(xMin, yMin, xMax, yMax),
                advances = advances,
                cmap = cmap,
                postScriptName = psName,
                numGlyphs = numGlyphs
            )
        }

        private fun parseCmap(buf: ByteBuffer, cmapOffset: Int, bytes: ByteArray): Map<Int, Int> {
            val count = buf.getShort(cmapOffset + 2).toInt() and 0xFFFF
            var best: Int = -1
            var bestScore = -1
            for (i in 0 until count) {
                val rec = cmapOffset + 4 + i * 8
                val platform = buf.getShort(rec).toInt() and 0xFFFF
                val encoding = buf.getShort(rec + 2).toInt() and 0xFFFF
                val subOffset = cmapOffset + buf.getInt(rec + 4)
                val format = buf.getShort(subOffset).toInt() and 0xFFFF
                val score = when {
                    format == 12 && platform == 3 && encoding == 10 -> 100
                    format == 4 && platform == 3 && encoding == 1 -> 80
                    format == 12 -> 70
                    format == 4 -> 50
                    format == 6 -> 30
                    else -> 0
                }
                if (score > bestScore) {
                    bestScore = score
                    best = subOffset
                }
            }
            if (best < 0) return emptyMap()
            return when (buf.getShort(best).toInt() and 0xFFFF) {
                4 -> parseFormat4(buf, best)
                12 -> parseFormat12(buf, best)
                6 -> parseFormat6(buf, best)
                else -> emptyMap()
            }
        }

        private fun parseFormat4(buf: ByteBuffer, off: Int): Map<Int, Int> {
            val map = HashMap<Int, Int>(512)
            val segCountX2 = buf.getShort(off + 6).toInt() and 0xFFFF
            val segCount = segCountX2 / 2
            val endBase = off + 14
            val startBase = endBase + segCountX2 + 2
            val deltaBase = startBase + segCountX2
            val rangeBase = deltaBase + segCountX2
            for (i in 0 until segCount) {
                val end = buf.getShort(endBase + i * 2).toInt() and 0xFFFF
                val start = buf.getShort(startBase + i * 2).toInt() and 0xFFFF
                if (start > end || end == 0xFFFF) continue
                val delta = buf.getShort(deltaBase + i * 2).toInt()
                val rangeOffset = buf.getShort(rangeBase + i * 2).toInt() and 0xFFFF
                for (c in start..end) {
                    if (c == 0xFFFF) continue
                    val gid: Int = if (rangeOffset == 0) {
                        (c + delta) and 0xFFFF
                    } else {
                        val addr = rangeBase + i * 2 + rangeOffset + (c - start) * 2
                        if (addr + 1 >= buf.capacity()) continue
                        var g = buf.getShort(addr).toInt() and 0xFFFF
                        if (g != 0) g = (g + delta) and 0xFFFF
                        g
                    }
                    if (gid != 0) map[c] = gid
                }
            }
            return map
        }

        private fun parseFormat12(buf: ByteBuffer, off: Int): Map<Int, Int> {
            val map = HashMap<Int, Int>(1024)
            val groups = buf.getInt(off + 12)
            var p = off + 16
            repeat(groups) {
                val start = buf.getInt(p)
                val end = buf.getInt(p + 4)
                val startGlyph = buf.getInt(p + 8)
                p += 12
                if (end - start > 0x20000) return@repeat
                for (c in start..end) {
                    map[c] = startGlyph + (c - start)
                }
            }
            return map
        }

        private fun parseFormat6(buf: ByteBuffer, off: Int): Map<Int, Int> {
            val map = HashMap<Int, Int>()
            val first = buf.getShort(off + 6).toInt() and 0xFFFF
            val count = buf.getShort(off + 8).toInt() and 0xFFFF
            for (i in 0 until count) {
                val gid = buf.getShort(off + 10 + i * 2).toInt() and 0xFFFF
                if (gid != 0) map[first + i] = gid
            }
            return map
        }

        private fun readPostScriptName(buf: ByteBuffer, nameOffset: Int?, bytes: ByteArray): String? {
            if (nameOffset == null) return null
            val count = buf.getShort(nameOffset + 2).toInt() and 0xFFFF
            val stringOffset = nameOffset + (buf.getShort(nameOffset + 4).toInt() and 0xFFFF)
            for (i in 0 until count) {
                val rec = nameOffset + 6 + i * 12
                val platform = buf.getShort(rec).toInt() and 0xFFFF
                val nameId = buf.getShort(rec + 6).toInt() and 0xFFFF
                val length = buf.getShort(rec + 8).toInt() and 0xFFFF
                val offset = buf.getShort(rec + 10).toInt() and 0xFFFF
                if (nameId != 6) continue
                if (platform == 1 || platform == 3) {
                    // platform 3 (Windows) хранит имя в UTF-16BE, platform 1 — в ASCII/MacRoman.
                    val text = if (platform == 3) {
                        String(bytes, stringOffset + offset, length, Charsets.UTF_16BE)
                    } else {
                        String(bytes, stringOffset + offset, length, Charsets.US_ASCII)
                    }
                    val safe = text.filter { it in 'A'..'Z' || it in 'a'..'z' || it.isDigit() || it == '-' || it == '_' }
                    if (safe.isNotBlank()) return safe
                }
            }
            return null
        }
    }
}
