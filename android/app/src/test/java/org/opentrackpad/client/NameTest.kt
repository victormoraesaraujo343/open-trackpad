package org.opentrackpad.client

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Suggesting a name for a copy.
 *
 * Small, and worth pinning: somebody duplicating twice in a row is the ordinary
 * case, and an off-by-one here hands them a name their own last copy is using.
 */
class NameTest {

    private fun suggest(existing: List<String>) = NamePanel.suggest(
        existing = existing,
        first = "Desktop copy",
        numbered = { "Desktop copy $it" },
    )

    @Test
    fun `the first copy takes the plain name`() {
        assertEquals("Desktop copy", suggest(listOf("Desktop", "Browser")))
    }

    @Test
    fun `a second copy counts past the first`() {
        assertEquals("Desktop copy 2", suggest(listOf("Desktop", "Desktop copy")))
        assertEquals(
            "Desktop copy 3",
            suggest(listOf("Desktop", "Desktop copy", "Desktop copy 2")),
        )
    }

    @Test
    fun `it skips a gap rather than reusing a number`() {
        // Somebody who deleted "Desktop copy 2" should not have the next copy
        // land on a name they may still recognise as the deleted one.
        assertEquals(
            "Desktop copy 4",
            suggest(listOf("Desktop copy", "Desktop copy 2", "Desktop copy 3")),
        )
    }

    @Test
    fun `capitals do not make a different profile`() {
        // Two profiles differing only in case are two nobody can tell apart on
        // a rail, so the suggestion treats them as the same name.
        assertEquals("Desktop copy 2", suggest(listOf("desktop COPY")))
    }
}
