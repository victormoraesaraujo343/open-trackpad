package org.opentrackpad.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the settings screen writes, and what survives being read back.
 *
 * Every control on that screen writes through immediately rather than waiting
 * for a Save, so the file is the state. These check the round trip and the two
 * ways a file can be wrong: a value from a later version, and one from no
 * version at all.
 */
class SettingsTest {

    private val everything = ProfileStore.Stored(
        settings = Settings(
            activeProfile = "Browser",
            shortcutSide = Side.LEFT,
            keepScreenAwake = false,
            haptics = false,
            fadeWhenIdle = false,
            returnToPadSeconds = 15,
        ),
        profiles = DefaultProfiles.all,
    )

    @Test
    fun `every setting survives being written and read`() {
        val back = ProfileStore.decode(ProfileStore.encode(everything))
        assertEquals(everything.settings, back.settings)
        assertEquals(everything.profiles, back.profiles)
    }

    @Test
    fun `a file from before these settings existed keeps the rest`() {
        // The two newest fields are absent from anything the previous version
        // wrote, and losing somebody's handedness because of that would be a
        // poor trade for two defaults.
        val old = buildString {
            appendLine("setting\tactive\tMedia")
            appendLine("setting\tside\tleft")
            appendLine("setting\thaptics\tfalse")
            appendLine("profile\tMedia")
            appendLine("slot\tPlay\tplaypause")
        }
        val back = ProfileStore.decode(old).settings
        assertEquals("Media", back.activeProfile)
        assertEquals(Side.LEFT, back.shortcutSide)
        assertEquals(false, back.haptics)
        assertEquals(DefaultProfiles.settings.fadeWhenIdle, back.fadeWhenIdle)
        assertEquals(DefaultProfiles.settings.returnToPadSeconds, back.returnToPadSeconds)
    }

    @Test
    fun `an absurd wait is brought back into range rather than refused`() {
        fun waitFrom(value: String) = ProfileStore
            .decode("setting\treturn\t$value\nprofile\tP\nslot\tA\tescape\n")
            .settings.returnToPadSeconds

        assertEquals(0, waitFrom("-5"))
        assertEquals(Settings.MAX_RETURN_SECONDS, waitFrom("999999"))
        assertEquals(45, waitFrom("45"))
        // Not a number at all: the default, not a crash and not zero, since
        // zero silently means "never" and would look like a deliberate choice.
        assertEquals(DefaultProfiles.settings.returnToPadSeconds, waitFrom("soon"))
    }

    @Test
    fun `never is a real choice and is offered`() {
        assertTrue(0 in Settings.RETURN_CHOICES)
        // Each choice has to round-trip, or the screen would show a wait the
        // person did not pick after they picked one of these.
        for (choice in Settings.RETURN_CHOICES) {
            val stored = everything.copy(settings = everything.settings.copy(returnToPadSeconds = choice))
            assertEquals(
                choice,
                ProfileStore.decode(ProfileStore.encode(stored)).settings.returnToPadSeconds,
            )
        }
    }
}
