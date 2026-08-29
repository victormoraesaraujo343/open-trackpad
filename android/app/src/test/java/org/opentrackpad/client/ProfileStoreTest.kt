package org.opentrackpad.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileStoreTest {

    private val stored = ProfileStore.Stored(
        settings = Settings(
            activeProfile = "Browser",
            shortcutSide = Side.LEFT,
            keepScreenAwake = false,
            haptics = false,
        ),
        profiles = DefaultProfiles.all,
    )

    @Test
    fun `what is written comes back unchanged`() {
        assertEquals(stored, ProfileStore.decode(ProfileStore.encode(stored)))
    }

    @Test
    fun `an empty file yields the defaults`() {
        val recovered = ProfileStore.decode("")
        assertEquals(DefaultProfiles.all, recovered.profiles)
        assertEquals(DefaultProfiles.settings, recovered.settings)
    }

    @Test
    fun `a damaged file costs customisation, not the app`() {
        val recovered = ProfileStore.decode("this is not\na settings file\n ")
        assertEquals(DefaultProfiles.all, recovered.profiles)
        assertTrue(recovered.settings.activeProfile.isNotBlank())
    }

    @Test
    fun `lines from a later version are skipped rather than fatal`() {
        val later = ProfileStore.encode(stored) +
            line("gesture", "pinch", "zoom") +
            line("setting", "future", "true")
        assertEquals(stored, ProfileStore.decode(later))
    }

    @Test
    fun `an active profile that no longer exists falls back to a real one`() {
        val text = line("setting", "active", "Deleted") +
            line("profile", "Kept") +
            line("slot", "Copy", "ctrl+c")
        assertEquals("Kept", ProfileStore.decode(text).settings.activeProfile)
    }

    @Test
    fun `a profile with no shortcuts is not kept`() {
        val text = line("profile", "Empty") +
            line("profile", "Real") +
            line("slot", "Copy", "ctrl+c")
        assertEquals(listOf("Real"), ProfileStore.decode(text).profiles.map { it.name })
    }

    @Test
    fun `slots outside a profile are ignored rather than misfiled`() {
        val text = line("slot", "Stray", "ctrl+c") +
            line("profile", "Real") +
            line("slot", "Copy", "ctrl+c")
        val recovered = ProfileStore.decode(text)
        assertEquals(listOf("Copy"), recovered.profiles.single().shortcuts.map { it.label })
    }

    @Test
    fun `a truncated line is skipped rather than half read`() {
        val text = line("setting", "active") +
            line("profile") +
            line("profile", "Real") +
            line("slot", "OnlyALabel") +
            line("slot", "Copy", "ctrl+c")
        val recovered = ProfileStore.decode(text)
        assertEquals(listOf("Copy"), recovered.profiles.single().shortcuts.map { it.label })
    }

    @Test
    fun `labels with spaces survive the round trip`() {
        val spaced = ProfileStore.Stored(
            settings = Settings("P"),
            profiles = listOf(
                Profile("P", listOf(Slot("Volume up", Action.KeyChord("volumeup"))))
            ),
        )
        assertEquals(spaced, ProfileStore.decode(ProfileStore.encode(spaced)))
    }

    private fun line(vararg fields: String) = fields.joinToString("\t") + "\n"
}
