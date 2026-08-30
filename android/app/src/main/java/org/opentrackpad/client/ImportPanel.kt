package org.opentrackpad.client

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The review screen for shortcuts this computer already has.
 *
 * Seventy-five is too many to tick one by one and too many to accept blind, so
 * the host marks the useful ones and they arrive ticked. Everything else is
 * browsable by what it controls.
 *
 * Its own class rather than more of `MainActivity`, because the screen owns a
 * real amount of state — which group is open, and which candidates are ticked —
 * and none of it means anything to the trackpad.
 */
class ImportPanel(private val root: View) {

    /** Accept these, in this offer. */
    var onAccept: ((generation: Long, ids: List<Int>) -> Unit)? = null

    /** Leave without taking any. */
    var onDismiss: (() -> Unit)? = null

    private val inflater = LayoutInflater.from(root.context)
    private val groupList: LinearLayout = root.findViewById(R.id.import_group_list)
    private val rows: LinearLayout = root.findViewById(R.id.import_rows)
    private val tally: TextView = root.findViewById(R.id.import_tally)
    private val groupTitle: TextView = root.findViewById(R.id.import_group_title)
    private val groupTally: TextView = root.findViewById(R.id.import_group_tally)
    private val chooseAll: TextView = root.findViewById(R.id.import_choose_all)
    private val add: TextView = root.findViewById(R.id.import_add)

    private var offer: List<Pair<ShortcutGroup?, List<Candidate>>> = emptyList()
    private var generation = -1L
    private var showing = 0

    /**
     * Which candidates are ticked.
     *
     * Held here rather than on the candidates themselves, because the offer can
     * be replaced under the screen — the host re-sends the whole picture when
     * anything changes — and somebody's ticks should survive that if the same
     * shortcuts are still on offer.
     */
    private val chosen = mutableSetOf<Int>()

    init {
        root.findViewById<View>(R.id.import_back).setOnClickListener { onDismiss?.invoke() }
        root.findViewById<View>(R.id.import_skip).setOnClickListener { onDismiss?.invoke() }
        add.setOnClickListener {
            val ids = chosen.toList()
            if (ids.isNotEmpty()) onAccept?.invoke(generation, ids)
        }
        chooseAll.setOnClickListener {
            val here = offer.getOrNull(showing)?.second.orEmpty()
            // One button doing both directions, because a screen where "Choose
            // all" has no opposite makes a mis-tap unrecoverable except one row
            // at a time.
            if (here.all { it.id in chosen }) chosen.removeAll(here.map { it.id }.toSet())
            else chosen.addAll(here.map { it.id })
            draw()
        }
    }

    /**
     * Takes a new offer.
     *
     * Ticks are carried across by id where the same shortcut is still offered.
     * A fresh offer pre-ticks what the host recommends — nine of seventy-five
     * on Victor's machine — because deciding which dozen is worth having is
     * knowledge the host has and the phone does not.
     */
    fun show(state: LibraryState) {
        val fresh = state.offerGeneration != generation
        generation = state.offerGeneration
        offer = state.offerByGroup()
        val offered = state.candidates.map { it.id }.toSet()
        if (fresh) {
            chosen.clear()
            chosen.addAll(state.candidates.filter { it.recommended }.map { it.id })
        } else {
            chosen.retainAll(offered)
        }
        showing = showing.coerceIn(0, (offer.size - 1).coerceAtLeast(0))
        draw()
    }

    private fun draw() {
        val all = offer.sumOf { it.second.size }
        tally.text = root.context.getString(R.string.import_tally, all, chosen.size)
        add.text = root.context.getString(R.string.import_add, chosen.size)
        // Nothing ticked is not a state the button can act on, and a lime
        // button that does nothing is worse than a dim one that says so.
        add.isEnabled = chosen.isNotEmpty()
        add.alpha = if (chosen.isEmpty()) 0.4f else 1f

        drawGroups()
        drawRows()
    }

    private fun drawGroups() {
        groupList.removeAllViews()
        for ((index, entry) in offer.withIndex()) {
            val (group, candidates) = entry
            val row = inflater.inflate(R.layout.row_import_group, groupList, false)
            row.findViewById<TextView>(R.id.group_name).text = nameOf(group)
            row.findViewById<TextView>(R.id.group_count).text = root.context.getString(
                R.string.import_group_count,
                candidates.count { it.id in chosen },
                candidates.size,
            )
            row.isActivated = index == showing
            row.setOnClickListener {
                showing = index
                draw()
            }
            groupList.addView(row)
        }
    }

    private fun drawRows() {
        rows.removeAllViews()
        val (group, here) = offer.getOrNull(showing) ?: (null to emptyList())
        groupTitle.text = nameOf(group)
        groupTally.text = root.context.getString(
            R.string.import_here,
            here.size,
            here.count { it.id in chosen },
        )
        chooseAll.text = root.context.getString(
            if (here.isNotEmpty() && here.all { it.id in chosen }) R.string.import_choose_none
            else R.string.import_choose_all
        )

        for (candidate in here) {
            val row = inflater.inflate(R.layout.row_import_candidate, rows, false)
            row.findViewById<TextView>(R.id.candidate_name).text = candidate.name
            row.findViewById<TextView>(R.id.candidate_chord).text = candidate.chord
            val ticked = candidate.id in chosen
            row.isActivated = ticked
            row.findViewById<View>(R.id.candidate_tick).visibility =
                if (ticked) View.VISIBLE else View.INVISIBLE
            row.setOnClickListener {
                if (!chosen.add(candidate.id)) chosen.remove(candidate.id)
                draw()
            }
            (row.layoutParams as ViewGroup.MarginLayoutParams).topMargin =
                if (rows.childCount == 0) 0 else (5 * root.resources.displayMetrics.density).toInt()
            rows.addView(row)
        }
    }

    /**
     * What a group is called.
     *
     * A group with no name is everything the person recorded themselves, which
     * the host cannot label because nobody told it what those are for.
     */
    private fun nameOf(group: ShortcutGroup?): String = root.context.getString(
        when (group) {
            ShortcutGroup.WINDOWS -> R.string.group_windows
            ShortcutGroup.DESKTOP -> R.string.group_desktop
            ShortcutGroup.SCREENSHOT -> R.string.group_screenshot
            ShortcutGroup.SOUND -> R.string.group_sound
            ShortcutGroup.MEDIA -> R.string.group_media
            ShortcutGroup.SESSION -> R.string.group_session
            ShortcutGroup.POWER -> R.string.group_power
            ShortcutGroup.KEYBOARD -> R.string.group_keyboard
            ShortcutGroup.ACCESSIBILITY -> R.string.group_accessibility
            ShortcutGroup.TEXT -> R.string.group_text
            ShortcutGroup.BROWSER -> R.string.group_browser
            ShortcutGroup.TERMINAL -> R.string.group_terminal
            ShortcutGroup.OTHER -> R.string.group_other
            null -> R.string.group_mine
        }
    )
}
