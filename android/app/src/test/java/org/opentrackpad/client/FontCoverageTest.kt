package org.opentrackpad.client

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * Every character the app can put on a rail is in the fonts it ships.
 *
 * This exists because it was got wrong. The bundled faces are subset to keep
 * them small, the label "Vol −" was written with a real minus sign, and U+2212
 * was not in the subset — so the button would have drawn a blank box, on a
 * surface whose whole purpose is being readable at a glance.
 *
 * It reads the actual font files rather than an allowlist of characters
 * somebody remembered to update, because the allowlist is the thing that was
 * wrong. `tools/build-fonts.sh` is how the files are regenerated when this
 * fails.
 */
class FontCoverageTest {

    private val fonts = File("src/main/res/font")

    @Test
    fun `every default label can actually be drawn`() {
        val faces = fonts.listFiles { file -> file.extension == "ttf" }
        assertTrue("no fonts found in ${fonts.absolutePath}", !faces.isNullOrEmpty())

        for (face in faces!!) {
            val covered = charactersIn(face)
            for (profile in DefaultProfiles.all) {
                for (slot in profile.shortcuts.filterNotNull()) {
                    for (character in slot.label) {
                        assertTrue(
                            "${face.name} cannot draw '$character' " +
                                "(U+%04X) from the label \"${slot.label}\"".format(character.code),
                            character.code in covered,
                        )
                    }
                }
            }
        }
    }

    /**
     * The code points a TrueType file has a glyph for.
     *
     * Walks `cmap` far enough to answer that and no further: formats 4 and 12
     * are what a subset font uses, and anything else is treated as unreadable
     * rather than guessed at, so a surprising file fails loudly.
     */
    private fun charactersIn(file: File): Set<Int> = RandomAccessFile(file, "r").use { font ->
        font.seek(4)
        val tables = font.readUnsignedShort()
        var cmap = 0L
        font.seek(12)
        repeat(tables) {
            val tag = ByteArray(4).also { font.readFully(it) }.decodeToString()
            font.readInt() // checksum
            val offset = font.readInt().toLong() and 0xFFFFFFFFL
            font.readInt() // length
            if (tag == "cmap") cmap = offset
        }
        assertTrue("${file.name} has no cmap", cmap != 0L)

        font.seek(cmap + 2)
        val encodings = font.readUnsignedShort()
        val subtables = (0 until encodings).map {
            font.seek(cmap + 4 + it * 8L)
            font.readUnsignedShort() // platform
            font.readUnsignedShort() // encoding
            cmap + (font.readInt().toLong() and 0xFFFFFFFFL)
        }

        val covered = mutableSetOf<Int>()
        for (subtable in subtables) {
            font.seek(subtable)
            when (font.readUnsignedShort()) {
                4 -> {
                    font.readUnsignedShort() // length
                    font.readUnsignedShort() // language
                    val segments = font.readUnsignedShort() / 2
                    font.skipBytes(6) // searchRange, entrySelector, rangeShift
                    val ends = IntArray(segments) { font.readUnsignedShort() }
                    font.readUnsignedShort() // reservedPad
                    val starts = IntArray(segments) { font.readUnsignedShort() }
                    // The deltas and range offsets decide *which* glyph, not
                    // whether there is one, and only the latter is asked here.
                    for (index in 0 until segments) {
                        if (starts[index] > ends[index] || ends[index] == 0xFFFF) continue
                        for (code in starts[index]..ends[index]) covered += code
                    }
                }

                12 -> {
                    font.skipBytes(10) // reserved, length, language
                    val groups = font.readInt()
                    repeat(groups) {
                        val start = font.readInt()
                        val end = font.readInt()
                        font.readInt() // startGlyphID
                        if (start <= end) for (code in start..end) covered += code
                    }
                }
            }
        }
        assertTrue("${file.name} has no readable cmap subtable", covered.isNotEmpty())
        covered
    }
}
