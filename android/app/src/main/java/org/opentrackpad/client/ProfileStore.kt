package org.opentrackpad.client

import java.io.File

/**
 * Reads and writes what the user has chosen.
 *
 * A tab-separated line format rather than JSON. It is a handful of fields, it
 * reads clearly in a bug report, it needs no dependency, and encoding and
 * decoding are ordinary functions that can be tested without a device.
 *
 * Unknown lines are skipped rather than rejected, so a file written by a later
 * version loses the parts this one does not understand instead of everything.
 */
object ProfileStore {

    /** Labels and chords are single fields; neither may contain a tab. */
    private const val FIELD = "\t"

    data class Stored(val settings: Settings, val profiles: List<Profile>)

    fun encode(stored: Stored): String = buildString {
        val settings = stored.settings
        appendLine(listOf("setting", "active", settings.activeProfile).joinToString(FIELD))
        appendLine(
            listOf("setting", "side", settings.shortcutSide.name.lowercase()).joinToString(FIELD)
        )
        appendLine(listOf("setting", "awake", "${settings.keepScreenAwake}").joinToString(FIELD))
        appendLine(listOf("setting", "haptics", "${settings.haptics}").joinToString(FIELD))
        appendLine(listOf("setting", "fade", "${settings.fadeWhenIdle}").joinToString(FIELD))
        appendLine(listOf("setting", "return", "${settings.returnToPadSeconds}").joinToString(FIELD))
        for (profile in stored.profiles) {
            appendLine(listOf("profile", profile.name).joinToString(FIELD))
            for (slot in profile.shortcuts) {
                val chord = (slot.action as? Action.KeyChord)?.chord ?: continue
                appendLine(listOf("slot", slot.label, chord).joinToString(FIELD))
            }
        }
    }

    /**
     * Parses a stored file, falling back to the defaults for anything missing
     * or damaged.
     *
     * A corrupt settings file should cost the user their customisation, not the
     * use of the app.
     */
    fun decode(text: String): Stored {
        var active: String? = null
        var side: Side? = null
        var awake: Boolean? = null
        var haptics: Boolean? = null
        var fade: Boolean? = null
        var returnAfter: Int? = null
        val profiles = mutableListOf<Profile>()
        var name: String? = null
        var slots = mutableListOf<Slot>()

        fun finishProfile() {
            val finished = name ?: return
            if (slots.isNotEmpty()) profiles += Profile(finished, slots.toList())
            slots = mutableListOf()
        }

        for (line in text.lineSequence()) {
            val parts = line.split(FIELD)
            when (parts.firstOrNull()) {
                "setting" -> {
                    if (parts.size < 3) continue
                    when (parts[1]) {
                        "active" -> active = parts[2]
                        "side" -> side = Side.entries.find { it.name.equals(parts[2], true) }
                        "awake" -> awake = parts[2].toBooleanStrictOrNull()
                        "haptics" -> haptics = parts[2].toBooleanStrictOrNull()
                        "fade" -> fade = parts[2].toBooleanStrictOrNull()
                        // Clamped rather than refused: a file written by a
                        // later version may offer a longer wait than this one
                        // does, and the nearest sane value beats the default.
                        "return" -> returnAfter = parts[2].toIntOrNull()
                            ?.coerceIn(0, Settings.MAX_RETURN_SECONDS)
                    }
                }

                "profile" -> {
                    if (parts.size < 2 || parts[1].isBlank()) continue
                    finishProfile()
                    name = parts[1]
                }

                "slot" -> {
                    if (parts.size < 3 || name == null) continue
                    if (parts[1].isBlank() || parts[2].isBlank()) continue
                    slots += Slot(parts[1], Action.KeyChord(parts[2]))
                }
            }
        }
        finishProfile()

        val recovered = profiles.ifEmpty { DefaultProfiles.all }
        val fallback = DefaultProfiles.settings
        return Stored(
            settings = Settings(
                // An active profile naming something that no longer exists
                // would leave the rails empty with no way to fix it.
                activeProfile = active?.takeIf { chosen -> recovered.any { it.name == chosen } }
                    ?: recovered.first().name,
                shortcutSide = side ?: fallback.shortcutSide,
                keepScreenAwake = awake ?: fallback.keepScreenAwake,
                haptics = haptics ?: fallback.haptics,
                fadeWhenIdle = fade ?: fallback.fadeWhenIdle,
                returnToPadSeconds = returnAfter ?: fallback.returnToPadSeconds,
            ),
            profiles = recovered,
        )
    }

    fun load(file: File): Stored = if (file.exists()) decode(file.readText()) else decode("")

    /**
     * Writes through a temporary file, so an interrupted save leaves the old
     * settings rather than half of the new ones.
     */
    fun save(file: File, stored: Stored) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(encode(stored))
        temporary.renameTo(file)
    }
}
