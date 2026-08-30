package org.opentrackpad.client

import java.io.File
import java.io.RandomAccessFile

/**
 * Just enough of a TrueType file to measure a string.
 *
 * `head` for the em size, `hhea` for how many advances there are, `hmtx`
 * for the advances themselves, and `cmap` to turn characters into the
 * glyphs those advances belong to.
 */
/**
 * Measured from the file rather than estimated, and the margin is why.
 *
 * "Screenshot" fills 97% of the room a rail label has. Any approximation —
 * an average advance, a character count, a per-character constant — carries
 * an error comfortably larger than the 3% that separates fitting from
 * clipped, so it would have to be tuned generous enough to pass everything
 * or strict enough to fail things that fit. Either way it stops being a
 * test of anything. Reading `hmtx` costs a hundred lines once and is exact.
 */
class TypeMetrics(
    private val unitsPerEm: Int,
    private val advances: IntArray,
    private val glyphs: Map<Int, Int>,
) {
    /** How wide [text] is when the em is [size]. */
    fun width(text: String, size: Float): Float {
        var total = 0
        for (character in text) {
            val glyph = glyphs[character.code] ?: continue
            total += advances[glyph.coerceAtMost(advances.size - 1)]
        }
        return total.toFloat() / unitsPerEm * size
    }

    companion object {
        fun read(file: File): TypeMetrics = RandomAccessFile(file, "r").use { font ->
            val tables = mutableMapOf<String, Long>()
            font.seek(4)
            val count = font.readUnsignedShort()
            font.seek(12)
            repeat(count) {
                val tag = ByteArray(4).also { font.readFully(it) }.decodeToString()
                font.readInt()
                tables[tag] = font.readInt().toLong() and 0xFFFFFFFFL
                font.readInt()
            }

            font.seek(tables.getValue("head") + 18)
            val unitsPerEm = font.readUnsignedShort()

            font.seek(tables.getValue("hhea") + 34)
            val metricCount = font.readUnsignedShort()

            font.seek(tables.getValue("hmtx"))
            val advances = IntArray(metricCount) {
                val advance = font.readUnsignedShort()
                font.readShort() // left side bearing, which does not affect a run's width
                advance
            }

            TypeMetrics(unitsPerEm, advances, characterToGlyph(font, tables.getValue("cmap")))
        }

        /**
         * The character-to-glyph map, from whichever subtable carries it.
         *
         * Format 4 is what a subset Latin font uses and is the fiddly one:
         * the glyph is either an offset from a delta or an index into an
         * array reached through `idRangeOffset`, and getting that wrong
         * gives plausible-looking widths for the wrong glyphs.
         */
        private fun characterToGlyph(font: RandomAccessFile, cmap: Long): Map<Int, Int> {
            font.seek(cmap + 2)
            val subtables = font.readUnsignedShort()
            val offsets = (0 until subtables).map {
                font.seek(cmap + 4 + it * 8L)
                font.readUnsignedShort()
                font.readUnsignedShort()
                cmap + (font.readInt().toLong() and 0xFFFFFFFFL)
            }

            val map = mutableMapOf<Int, Int>()
            for (subtable in offsets) {
                font.seek(subtable)
                when (font.readUnsignedShort()) {
                    4 -> readFormat4(font, subtable, map)
                    12 -> readFormat12(font, map)
                }
            }
            return map
        }

        private fun readFormat4(
            font: RandomAccessFile,
            subtable: Long,
            into: MutableMap<Int, Int>,
        ) {
            font.readUnsignedShort() // length
            font.readUnsignedShort() // language
            val segments = font.readUnsignedShort() / 2
            font.skipBytes(6)

            val ends = IntArray(segments) { font.readUnsignedShort() }
            font.readUnsignedShort()
            val starts = IntArray(segments) { font.readUnsignedShort() }
            val deltas = IntArray(segments) { font.readShort().toInt() }
            val rangeOffsetAt = font.filePointer
            val rangeOffsets = IntArray(segments) { font.readUnsignedShort() }

            for (index in 0 until segments) {
                if (starts[index] > ends[index] || starts[index] == 0xFFFF) continue
                for (code in starts[index]..ends[index]) {
                    val glyph = if (rangeOffsets[index] == 0) {
                        (code + deltas[index]) and 0xFFFF
                    } else {
                        // The offset is measured from the entry itself, which
                        // is why it needs the address of this segment's slot
                        // rather than the start of the table.
                        val at = rangeOffsetAt + index * 2L + rangeOffsets[index] +
                            (code - starts[index]) * 2L
                        font.seek(at)
                        val found = font.readUnsignedShort()
                        if (found == 0) 0 else (found + deltas[index]) and 0xFFFF
                    }
                    if (glyph != 0) into.putIfAbsent(code, glyph)
                }
            }
        }

        private fun readFormat12(font: RandomAccessFile, into: MutableMap<Int, Int>) {
            font.skipBytes(10)
            repeat(font.readInt()) {
                val start = font.readInt()
                val end = font.readInt()
                val glyph = font.readInt()
                if (start <= end) {
                    for (code in start..end) into.putIfAbsent(code, glyph + (code - start))
                }
            }
        }
    }
}

