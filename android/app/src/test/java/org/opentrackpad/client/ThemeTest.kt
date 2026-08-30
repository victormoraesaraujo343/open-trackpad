package org.opentrackpad.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every theme answers for every colour the app draws with.
 *
 * The point of routing colours through theme attributes is that a second visual
 * identity becomes a second style rather than an edit everywhere. The cost of
 * that indirection is a new way to be wrong: a theme that omits an attribute
 * compiles, installs, and fails at the first screen that asks for it.
 *
 * [Palette] refuses to start rather than drawing one thing black — an app half
 * in one identity and half in another is harder to diagnose than one that does
 * not run — but "fails on the device" is a poor place to find out. This finds it
 * in the build, which is where somebody adding the skin will be standing.
 *
 * Reads the resource files rather than the generated `R` class, because the
 * question is whether the XML is complete and `R` cannot answer that.
 */
class ThemeTest {

    private val res = File("src/main/res")

    private fun declared(): Set<String> =
        Regex("""<attr name="(otp\w+)"""")
            .findAll(File(res, "values/attrs.xml").readText())
            .map { it.groupValues[1] }
            .toSet()

    private fun themes(): Map<String, Set<String>> {
        val text = File(res, "values/themes.xml").readText()
        return Regex("""<style name="([^"]+)"[^>]*>(.*?)</style>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .associate { style ->
                style.groupValues[1] to
                    Regex("""<item name="(otp\w+)">""")
                        .findAll(style.groupValues[2])
                        .map { it.groupValues[1] }
                        .toSet()
            }
    }

    @Test
    fun `every theme answers for every colour`() {
        val wanted = declared()
        assertTrue("no attributes declared", wanted.isNotEmpty())
        for ((theme, answered) in themes()) {
            assertEquals("$theme does not answer for", emptySet<String>(), wanted - answered)
        }
    }

    @Test
    fun `no theme answers for a colour nobody declared`() {
        // The other direction, which is how a rename leaves a dead item behind
        // that looks like it is doing something.
        val wanted = declared()
        for ((theme, answered) in themes()) {
            assertEquals("$theme answers for undeclared", emptySet<String>(), answered - wanted)
        }
    }

    @Test
    fun `nothing draws with a colour resource directly`() {
        // A `@color` cannot be overridden by a theme, so one left in a layout is
        // a spot that stays the default identity while everything around it
        // changes. Invisible until somebody switches themes and finds one
        // stubborn panel.
        val offenders = mutableListOf<String>()
        val looked = sequenceOf("layout", "drawable")
            .flatMap { File(res, it).listFiles().orEmpty().asSequence() }
            .plus(File(res, "values/styles.xml"))
        for (file in looked) {
            if (file.readText().contains("@color/")) offenders += file.name
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `every colour the palette names exists`() {
        // Palette resolves by attribute, so a colour it names that no longer
        // exists fails at the theme rather than here — but a colour declared and
        // never pointed at anything is dead weight nobody will remove later.
        val colours = Regex("""<color name="([a-z_]+)">""")
            .findAll(File(res, "values/colors.xml").readText())
            .map { it.groupValues[1] }
            .toList()
        val themeText = File(res, "values/themes.xml").readText()
        val unused = colours.filterNot { themeText.contains("@color/$it<") }
        assertEquals(emptyList<String>(), unused)
    }
}
