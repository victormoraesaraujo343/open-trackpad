package org.opentrackpad.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The windows domain: parsing it, holding it, and putting it on a rail.
 *
 * The tests that matter most here are about **order** and about **titles**. The
 * order is the only content this domain has, and a title is the string most
 * directly under a stranger's control anywhere in the protocol.
 */
class WindowsTest {

    private fun entry(line: String) =
        Windows.parse(line) as? WindowMessage.Window

    // -- the wire -------------------------------------------------------------

    @Test
    fun `an entry carries an id, an application and a decoded title`() {
        val message = entry(
            "ENTRY windows 1 window 5 firefox OpenTrackpad%20%E2%80%94%20Painel"
        )!!
        assertEquals(5, message.entry.id)
        assertEquals("firefox", message.entry.application)
        assertEquals("OpenTrackpad — Painel", message.entry.title)
    }

    @Test
    fun `a title cannot become a second line`() {
        // Percent-encoding keeps a newline from breaking the framing, which is
        // the host's half of this. It does not keep one out of the decoded
        // string, and a title is whatever a web page decided to call itself —
        // so an escaped control character is refused too, and a title that
        // tried to be two lines is simply not a title.
        assertNull(entry("ENTRY windows 1 window 5 firefox two%0Alines"))
        assertNull(entry("ENTRY windows 1 window 5 firefox two%0Dlines"))
        assertNull(entry("ENTRY windows 1 window 5 firefox two%09tabs"))
        // Ordinary text still arrives whole, including anything non-Latin.
        assertEquals(
            "Painel — ção",
            entry("ENTRY windows 1 window 5 firefox Painel%20%E2%80%94%20%C3%A7%C3%A3o")!!
                .entry.title,
        )
    }

    @Test
    fun `a malformed entry is refused rather than half-read`() {
        assertNull(entry("ENTRY windows 1 window 5 firefox"))
        assertNull(entry("ENTRY windows 1 window 5 firefox title extra"))
        assertNull(entry("ENTRY windows 1 shortcut 5 firefox title"))
        assertNull(entry("ENTRY windows one window 5 firefox title"))
        assertNull(entry("ENTRY windows 1 window five firefox title"))
    }

    @Test
    fun `another domain's lines are not ours`() {
        assertNull(Windows.parse("ENTRY audio 1 sink 3 1000 0 1 - - - Speakers"))
        assertNull(Windows.parse("SNAPSHOT shortcuts 4 12"))
    }

    @Test
    fun `switching is the only request`() {
        assertEquals("REQUEST 9 windows ACTIVATE 5", Windows.activate(9, 5))
        assertEquals("REQUEST 3 windows REFRESH", Windows.refresh(3))
    }

    // -- the picture ----------------------------------------------------------

    private fun stateWith(vararg open: Pair<Int, String>): WindowsState {
        val state = WindowsState()
        state.grant(true)
        state.apply(WindowMessage.Snapshot(1, open.size))
        for ((id, application) in open) {
            state.apply(WindowMessage.Window(1, WindowEntry(id, application, "a title")))
        }
        return state
    }

    @Test
    fun `the order the host gave is the order that is kept`() {
        // The whole point of the domain, and the thing most easily lost to a
        // tidy-looking sort. The host says most-recently-used first; anything
        // here that reordered would be a second opinion about the only fact
        // this domain carries.
        val state = stateWith(3 to "steam", 1 to "firefox", 2 to "konsole")
        assertEquals(listOf(3, 1, 2), state.windows.map { it.id })
    }

    @Test
    fun `using a window moves it to the front, and the rail follows`() {
        // The failure dc found in the obvious source: a list that looks right
        // and never reorders, which is correct for an hour and wrong forever
        // after. Asserted as a change rather than as an arrival.
        val state = stateWith(1 to "firefox", 2 to "konsole", 3 to "steam")
        val before = Rails.windows(state.onTheRail).map { it?.label }

        state.apply(WindowMessage.Snapshot(2, 3))
        for ((id, application) in listOf(3 to "steam", 1 to "firefox", 2 to "konsole")) {
            state.apply(WindowMessage.Window(2, WindowEntry(id, application, "a title")))
        }
        val after = Rails.windows(state.onTheRail).map { it?.label }

        assertEquals("steam", state.windows.first().application)
        assertTrue("the rail did not reorder: $before", before != after)
    }

    @Test
    fun `a picture from a generation never seen asks for the whole thing again`() {
        val state = stateWith(1 to "firefox")
        assertTrue(state.apply(WindowMessage.Window(9, WindowEntry(2, "konsole", "t"))))
    }

    @Test
    fun `an update from an old generation is ignored`() {
        val state = stateWith(1 to "firefox")
        assertEquals(false, state.apply(WindowMessage.Removed(0, 1)))
        assertEquals(1, state.windows.size)
    }

    @Test
    fun `a desktop that stops answering leaves no windows behind`() {
        // An old list is worse than none here: every button on it switches to
        // something that may be gone.
        val state = stateWith(1 to "firefox", 2 to "konsole")
        state.apply(WindowMessage.Unavailable("no-compositor"))
        assertEquals(emptyList<WindowEntry>(), state.windows)
    }

    @Test
    fun `losing the capability empties the rail rather than freezing it`() {
        val state = stateWith(1 to "firefox")
        state.grant(false)
        assertEquals(emptyList<WindowEntry>(), state.windows)
    }

    // -- the rail -------------------------------------------------------------

    @Test
    fun `the rail is four windows and a way to the rest`() {
        val state = stateWith(1 to "a", 2 to "b", 3 to "c", 4 to "d", 5 to "e", 6 to "f")
        val rail = Rails.windows(state.onTheRail)
        assertEquals(Rails.SLOTS, rail.size)
        assertEquals(listOf("a", "b", "c", "d"), rail.take(4).map { it?.label })
        assertEquals(SlotPress.AllWindows, rail.last()?.press)
    }

    @Test
    fun `fewer windows leave slots empty rather than moving the fifth`() {
        // Slot five is slot five whether the desktop has one window open or
        // twenty. A way out that moved would have to be looked for every time.
        val rail = Rails.windows(stateWith(1 to "a").onTheRail)
        assertEquals(Rails.SLOTS, rail.size)
        assertEquals(SlotPress.AllWindows, rail.last()?.press)
        assertEquals(listOf(null, null, null), rail.subList(1, 4))
    }

    @Test
    fun `a slot switches to the window it was drawn from`() {
        val rail = Rails.windows(stateWith(7 to "firefox").onTheRail)
        assertEquals(SlotPress.Switch(7), rail.first()?.press)
    }

    @Test
    fun `a rail slot says the application rather than the title`() {
        // A title is longer, more specific and completely under a stranger's
        // control. An application name is what somebody reaching for the phone
        // without looking is actually scanning for.
        val state = WindowsState()
        state.grant(true)
        state.apply(WindowMessage.Snapshot(1, 1))
        state.apply(
            WindowMessage.Window(1, WindowEntry(1, "firefox", "a very long page title indeed"))
        )
        assertEquals("firefox", Rails.windows(state.onTheRail).first()?.label)
    }

    @Test
    fun `an unknown application still gets a glyph`() {
        // Every window is a window, so there is always an honest answer.
        assertTrue(RailIcons.forWindow("some.unheard.of.thing").isNotEmpty())
        assertEquals(RailIcons.path("globe"), RailIcons.forWindow("firefox"))
        assertEquals(RailIcons.path("gear"), RailIcons.forWindow("systemsettings"))
    }
}
