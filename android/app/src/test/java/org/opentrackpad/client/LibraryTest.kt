package org.opentrackpad.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shortcut library and the import offer.
 *
 * The lines here are the ones `open-trackpad-dc` sent from Victor's own
 * machine, so these check the format as it actually behaves rather than as I
 * read it.
 */
class LibraryTest {

    @Test
    fun `the host's own example lines read`() {
        val convention = Library.parse("ENTRY shortcuts 1 shortcut 1 ctrl+c convention text Copy")
        assertEquals(
            LibraryMessage.Shortcut(
                1,
                LibraryEntry(1, "ctrl+c", Origin.CONVENTION, ShortcutGroup.TEXT, "Copy"),
                changed = false,
            ),
            convention,
        )

        val mine = Library.parse("ENTRY shortcuts 1 shortcut 9 super+j recorded - Mine")
                as LibraryMessage.Shortcut
        assertEquals(Origin.RECORDED, mine.entry.origin)
        assertNull("a recorded shortcut has no group", mine.entry.group)

        val candidate = Library.parse("ENTRY import 1 candidate 4 super+l session 1 Lock%20Session")
                as LibraryMessage.Offer
        assertEquals(Candidate(4, "super+l", ShortcutGroup.SESSION, true, "Lock Session"), candidate.candidate)
    }

    @Test
    fun `what may be renamed and deleted is not the same list`() {
        // Deliberately asymmetric. A convention is rewritten from a seed table,
        // so a rename would silently reappear; a deleted import returns to the
        // next offer, so delete would not mean what pressing it suggests.
        assertFalse(Origin.CONVENTION.renamable)
        assertFalse(Origin.CONVENTION.deletable)
        assertTrue(Origin.IMPORTED.renamable)
        assertFalse(Origin.IMPORTED.deletable)
        assertTrue(Origin.RECORDED.renamable)
        assertTrue(Origin.RECORDED.deletable)
    }

    @Test
    fun `a request the host would refuse is never sent`() {
        val convention = LibraryEntry(1, "ctrl+c", Origin.CONVENTION, ShortcutGroup.TEXT, "Copy")
        val imported = LibraryEntry(2, "super+w", Origin.IMPORTED, ShortcutGroup.WINDOWS, "Windows")
        val recorded = LibraryEntry(3, "super+j", Origin.RECORDED, null, "Mine")

        assertNull(Library.rename(1, convention, "Anything"))
        assertNull(Library.delete(1, convention))
        assertNull("an import cannot be deleted", Library.delete(1, imported))

        assertEquals(
            "REQUEST 1 shortcuts RENAME 2 Tile%20left",
            Library.rename(1, imported, "Tile left"),
        )
        assertEquals("REQUEST 2 shortcuts DELETE 3", Library.delete(2, recorded))
        // A blank name is not a rename, it is a way to lose a button's label.
        assertNull(Library.rename(3, recorded, "   "))
    }

    @Test
    fun `accepting carries the generation and refuses to send nothing`() {
        assertEquals(
            "REQUEST 5 import ACCEPT 3 4,9,17",
            Library.accept(5, generation = 3, ids = listOf(4, 9, 17)),
        )
        // Accepting nothing is the same as not pressing the button, and asking
        // the host to do nothing is not a request.
        assertNull(Library.accept(5, generation = 3, ids = emptyList()))
    }

    @Test
    fun `a recorded shortcut claiming a group is refused`() {
        // The person never said what theirs is for. A host sending one has
        // guessed, and a guess is what the dash exists to prevent.
        assertNull(Library.parse("ENTRY shortcuts 1 shortcut 9 super+j recorded windows Mine"))
    }

    @Test
    fun `malformed entries are refused`() {
        assertNull(Library.parse("ENTRY shortcuts 1 shortcut 1 ctrl+c nonsense text Copy"))
        assertNull(Library.parse("ENTRY shortcuts 1 shortcut 1 ctrl+c convention nonsense Copy"))
        assertNull(Library.parse("ENTRY import 1 candidate 4 super+l session 2 Lock"))
        assertNull(Library.parse("ENTRY shortcuts 1 candidate 1 ctrl+c convention text Copy"))
        assertNull(Library.parse("ENTRY import 1 shortcut 4 super+l session 1 Lock"))
        // Trailing and missing fields both.
        assertNull(Library.parse("ENTRY shortcuts 1 shortcut 1 ctrl+c convention text Copy extra"))
        assertNull(Library.parse("ENTRY shortcuts 1 shortcut 1 ctrl+c convention text"))
    }

    @Test
    fun `another domain's line is not ours to read`() {
        assertNull(Library.parse("ENTRY audio 1 output 53 950 0 1 - analog - Speakers"))
        assertNull(Audio.parse("ENTRY shortcuts 1 shortcut 1 ctrl+c convention text Copy"))
    }

    @Test
    fun `a name goes out escaped the same way it comes in`() {
        // A rename is as much free text as anything the host sends, and the
        // host closes the connection on a malformed line.
        assertEquals("Tile%20left", Wire.encode("Tile left"))
        assertEquals("100%25", Wire.encode("100%"))
        assertEquals("M%C3%BAsica", Wire.encode("Música"))
        assertEquals("%20", Wire.encode(""))
        for (name in listOf("Tile left", "100%", "Música", "a b c", "—dash—")) {
            assertEquals(name, Wire.decode(Wire.encode(name)))
        }
    }

    @Test
    fun `a generation decides whether an update still applies`() {
        assertEquals(Wire.Verdict.APPLY, Wire.verdict(held = 4, named = 4))
        assertEquals(Wire.Verdict.STALE, Wire.verdict(held = 4, named = 3))
        assertEquals(Wire.Verdict.MISSED, Wire.verdict(held = 4, named = 5))
    }
}
