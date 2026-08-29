package org.opentrackpad.client

import android.graphics.Path
import androidx.core.graphics.PathParser

/**
 * The glyphs the rails draw, as SVG path data on a 20-by-20 grid.
 *
 * Path data rather than one vector drawable per icon, for two reasons. The
 * strings are lifted verbatim from the screens they were drawn in, so a glyph
 * cannot drift from its design by being redrawn by hand; and a rail that will
 * eventually take its shortcuts from the host needs to pick an icon at runtime,
 * which a map does and a folder of resource ids does not.
 *
 * Every path is stroked, never filled, at 1.4 units of that same grid.
 */
object RailIcons {

    /** Drawn when nothing better is known: a blank key. The label carries it. */
    const val FALLBACK = "keys"

    private val PATHS = mapOf(
        // Lifted from the screens.
        "alt" to "M3.2 6.6h9.6M10.4 4.2 12.8 6.6l-2.4 2.4M16.8 13.4H7.2M9.6 11l-2.4 2.4L9.6 15.8",
        "app" to "M4 5.2h12v9.6H4zM4 8.4h12M7 5.2v3.2",
        "back" to "M16 10H4M9 5 4 10l5 5",
        "click" to "M10 3.4a5.4 5.4 0 0 1 5.4 5.4v2.6A5.4 5.4 0 0 1 10 16.8a5.4 5.4 0 0 1-5.4-5.4V8.8A5.4 5.4 0 0 1 10 3.4z" +
            "M10 3.6v5.2M10 8.8h5.4",
        "copy" to "M7.4 7.4h8v8h-8zM12.6 5.2V4.6a.6.6 0 0 0-.6-.6H5.2a.6.6 0 0 0-.6.6v6.8a.6.6 0 0 0 .6.6h.6",
        "esc" to "M12.6 6.6a4 4 0 1 0 0 6.8M3.4 10h4.2",
        "find" to "M12.6 12.6 16.4 16.4M8.8 14.2a5.4 5.4 0 1 0 0-10.8 5.4 5.4 0 0 0 0 10.8z",
        "gear" to "M4 6.4h5M13 6.4h3M4 13.6h3M11 13.6h5M11 4.4v4M7 11.6v4",
        "globe" to "M10 3.4a6.6 6.6 0 1 0 0 13.2 6.6 6.6 0 0 0 0-13.2zM3.4 10h13.2M10 3.4c3.4 3.6 3.4 9.6 0 13.2-3.4-3.6-3.4-9.6 0-13.2z",
        "grid" to "M3.6 3.6h5.2v5.2H3.6zM11.2 3.6h5.2v5.2h-5.2zM3.6 11.2h5.2v5.2H3.6zM11.2 11.2h5.2v5.2h-5.2z",
        "hold" to "M6 9V6.6a4 4 0 0 1 8 0V9M5 9h10v7H5z",
        "home" to "M3 9.4 10 3.4l7 6M5.3 8.6V16.4h9.4V8.6",
        "keys" to "M3.2 5.8h13.6v8.4H3.2zM6 8.4h.01M9 8.4h.01M12 8.4h.01M14.6 8.4h.01M6.6 11.6h6.8",
        "max" to "M3.4 7.2V3.4h3.8M12.8 3.4h3.8v3.8M16.6 12.8v3.8h-3.8M7.2 16.6H3.4v-3.8",
        "mic" to "M10 3.4a2 2 0 0 1 2 2v4.2a2 2 0 0 1-4 0V5.4a2 2 0 0 1 2-2zM6.2 9.4a3.8 3.8 0 0 0 7.6 0M10 13.4v3.2",
        "next" to "M5.4 4.6 12 10l-6.6 5.4zM14.6 4.6v10.8",
        "note" to "M7.4 14.2V5l7.2-1.4v9.2M7.4 14.2a1.8 1.8 0 1 1-3.6 0 1.8 1.8 0 0 1 3.6 0zM14.6 12.8a1.8 1.8 0 1 1-3.6 0 1.8 1.8 0 0 1 3.6 0z",
        "paste" to "M6.2 4.8h7.6v11.4H6.2zM8.2 4.8V3.4h3.6v1.4",
        "play" to "M6.4 4.2 15 10l-8.6 5.8z",
        "quick" to "M11.2 3 5.6 10.8h3.9L8.8 17l5.6-7.8h-3.9z",
        "scroll" to "M10 3.6v12.8M7.2 6.4 10 3.6l2.8 2.8M7.2 13.6 10 16.4l2.8-2.8",
        "shot" to "M4 6.6h3l1.2-1.8h3.6L17 6.6v8.8H4zM10 12.6a2.6 2.6 0 1 0 0-5.2 2.6 2.6 0 0 0 0 5.2",
        "space" to "M3.4 4.2h5.2v5.2H3.4zM11.4 4.2h5.2v5.2h-5.2zM3.4 11.4h5.2v5.2H3.4zM11.4 11.4h5.2v5.2h-5.2z",
        "super" to "M4 6.2h12v7.6H4zM7.4 6.2v7.6M12.6 6.2v7.6",
        "tab" to "M3.4 5h13.2v10H3.4zM3.4 8.2h13.2M7.6 5v3.2",
        "term" to "M3.4 4.6h13.2v10.8H3.4zM6 8.6l2.4 2-2.4 2M10.6 12.6h3.4",
        "undo" to "M7 6.4 4 9.4l3 3M4.4 9.4h8a3.6 3.6 0 0 1 0 7.2h-2",
        "vol" to "M4.2 8.2h2.6L10.4 5v10L6.8 11.8H4.2zM13.4 7.6a3.4 3.4 0 0 1 0 4.8",
        "win" to "M3.4 4.6h13.2v10.8H3.4zM3.4 7.8h13.2M6.6 4.6v3.2",

        // Drawn here, in the same hand, for shortcuts the screens never showed.
        "close" to "M5.6 5.6 14.4 14.4M14.4 5.6 5.6 14.4",
        "cut" to "M6.4 4.2 13.6 13.4M13.6 4.2 6.4 13.4M7.6 15.2a1.9 1.9 0 1 1-3.8 0 1.9 1.9 0 0 1 3.8 0z" +
            "M16.2 15.2a1.9 1.9 0 1 1-3.8 0 1.9 1.9 0 0 1 3.8 0z",
        "mute" to "M4.2 8.2h2.6L10.4 5v10L6.8 11.8H4.2zM13.2 8.2l3.6 3.6M16.8 8.2l-3.6 3.6",
        "newTab" to "M3.4 5h13.2v10H3.4zM3.4 8.2h13.2M7.6 5v3.2M12.6 11.6h3.2M14.2 10v3.2",
        "previous" to "M14.6 4.6 8 10l6.6 5.4zM5.4 4.6v10.8",
        // The undo arrow mirrored about the centre line, so the pair reads as
        // one shape facing two ways rather than two drawings of an arrow.
        "redo" to "M13 6.4 16 9.4l-3 3M15.6 9.4h-8a3.6 3.6 0 0 0 0 7.2h2",
        "reload" to "M16.2 10a6.2 6.2 0 1 1-1.9-4.4M16.6 3.4v3.4h-3.4",
        "volumeDown" to "M4.2 8.2h2.6L10.4 5v10L6.8 11.8H4.2zM13.2 10h3.6",
        "volumeUp" to "M4.2 8.2h2.6L10.4 5v10L6.8 11.8H4.2zM13.2 10h3.6M15 8.2v3.6",
    )

    /**
     * Which glyph goes with a chord.
     *
     * Keyed on the whole chord rather than the label: a label is written by
     * whoever built the profile and can say anything, while a chord is a fixed
     * name the host already validates. Anything unrecognised gets the blank key
     * and reads from its label, which is why this is allowed to be incomplete.
     */
    private val BY_CHORD = mapOf(
        "alt+tab" to "alt",
        "super" to "super",
        "ctrl+c" to "copy",
        "ctrl+v" to "paste",
        "ctrl+x" to "cut",
        "ctrl+z" to "undo",
        "ctrl+shift+z" to "redo",
        "alt+f4" to "close",
        "alt+left" to "back",
        "escape" to "esc",
        "ctrl+t" to "newTab",
        "ctrl+w" to "close",
        "ctrl+r" to "reload",
        "ctrl+f" to "find",
        "ctrl+l" to "globe",
        "f11" to "max",
        "print" to "shot",
        "playpause" to "play",
        "nexttrack" to "next",
        "previoustrack" to "previous",
        "volumeup" to "volumeUp",
        "volumedown" to "volumeDown",
        "mute" to "mute",
        "micmute" to "mic",
    )

    /** The path data for [name], or the blank key if there is no such glyph. */
    fun path(name: String): String = PATHS[name] ?: PATHS.getValue(FALLBACK)

    /**
     * The same glyph as a drawable path, parsed once and kept.
     *
     * Shared by everything that draws one — the rails and the Quick Ring show
     * the same shortcut in two places, and parsing it twice would be both
     * wasted and a way for them to drift. There are a few dozen glyphs in all,
     * so holding them costs a few kilobytes for the life of the process.
     */
    fun parsed(pathData: String): Path =
        GLYPHS.getOrPut(pathData) { PathParser.createPathFromPathData(pathData) }

    private val GLYPHS = HashMap<String, Path>()

    /** The glyph name for an action, which today is always a chord. */
    fun forAction(action: Action): String = when (action) {
        is Action.KeyChord -> BY_CHORD[action.chord.lowercase()] ?: FALLBACK
    }
}
