package org.opentrackpad.client

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * Every open window, in two columns, most recently used first.
 *
 * The rail shows four; this is the rest. It is the **only place a window title
 * appears** — on a rail there is no room and an application name is what
 * somebody scans for without looking, while here there is room and eleven
 * windows across three browsers is exactly when a title earns its place.
 *
 * ## Fixed cells, on purpose
 *
 * Every cell is the same size and every line inside it is a single line clipped
 * to that size. A title is whatever a web page decided to call itself, and it is
 * the string most directly under a stranger's control anywhere in this product;
 * nothing about it may decide where anything sits. [Wire.decode] already refuses
 * one carrying a control character, so this is the second of two defences rather
 * than the only one.
 *
 * ## The last row is cut
 *
 * The grid is clipped rather than scrolled, and a bar underneath says how much
 * of the list is showing. That is from the drawing and it is the right shape for
 * what this is: somebody looking for a window they used a minute ago finds it in
 * the first rows, and a scrolling list would invite hunting through a set that
 * reorders itself while you look at it.
 */
class AllWindowsPanel(private val root: View) {

    /** Switch to this window. */
    var onChoose: ((WindowEntry) -> Unit)? = null

    /** How this feels under a finger. Set by the activity. */
    var haptics: Haptics? = null

    private val inflater = LayoutInflater.from(root.context)
    private val grid: GridLayout = root.findViewById(R.id.all_windows_grid)
    private val count: TextView = root.findViewById(R.id.all_windows_count)
    private val shown: View = root.findViewById(R.id.all_windows_shown)
    private val extent: View = root.findViewById(R.id.all_windows_extent)

    private val artboard = Artboard.measure(
        root.resources.displayMetrics,
        root.resources.displayMetrics.widthPixels,
        root.resources.configuration.fontScale,
    )

    init {
        // The drawing's own padding and gaps, which cannot live in the layout:
        // they are artboard units, and a unit is a physical length that is not
        // known until this runs on a particular panel.
        val edge = artboard.size(PADDING)
        root.setPadding(edge, edge, edge, edge)
        (grid.layoutParams as ViewGroup.MarginLayoutParams).topMargin = artboard.size(GAP)
        (extent.layoutParams as ViewGroup.MarginLayoutParams).topMargin = artboard.size(GAP)
        extent.layoutParams.height = artboard.size(EXTENT_HEIGHT)
    }

    fun show(windows: List<WindowEntry>) {
        count.text = root.context.resources.getQuantityString(
            R.plurals.all_windows_open, windows.size, windows.size
        )
        grid.removeAllViews()

        val cell = artboard.size(CELL_HEIGHT)
        val columnGap = artboard.size(COLUMN_GAP)
        val rowGap = artboard.size(ROW_GAP)

        for ((index, window) in windows.withIndex()) {
            val view = inflater.inflate(R.layout.row_window, grid, false)
            view.findViewById<ImageView>(R.id.window_icon).setImageDrawable(
                RailIcons.drawable(
                    root.context,
                    RailIcons.forWindow(window.application),
                    Palette.of(root.context).secondary,
                    artboard.px(ICON),
                )
            )
            view.findViewById<TextView>(R.id.window_application).text = window.application
            view.findViewById<TextView>(R.id.window_title).text = window.title

            // The first is the one in focus: the list is most recently used
            // first and switching moves a window to the front, so "most
            // recently used" and "the one you are in" are the same window.
            view.findViewById<View>(R.id.window_here).visibility =
                if (index == 0) View.VISIBLE else View.INVISIBLE

            view.setOnClickListener {
                haptics?.press()
                haptics?.release()
                onChoose?.invoke(window)
            }

            val column = index % COLUMNS
            val row = index / COLUMNS
            view.layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(row),
                GridLayout.spec(column, 1f),
            ).apply {
                width = 0
                height = cell
                marginStart = if (column == 0) 0 else columnGap
                topMargin = if (row == 0) 0 else rowGap
            }
            grid.addView(view)
        }

        showExtent(windows.size, cell, rowGap)
    }

    /**
     * How much of the list is on screen, as a bar.
     *
     * Not a scrollbar — nothing scrolls. It answers the one question the cut
     * row raises, which is whether what you are looking for might be below it.
     * Measured after layout because the answer depends on how tall the grid
     * turned out, which is not known while it is being filled.
     */
    private fun showExtent(windows: Int, cell: Int, rowGap: Int) {
        grid.post {
            val rows = (windows + COLUMNS - 1) / COLUMNS
            val needed = rows * cell + (rows - 1).coerceAtLeast(0) * rowGap
            val room = grid.height
            val fraction =
                if (needed <= 0 || room <= 0) 1f
                else (room.toFloat() / needed).coerceIn(0f, 1f)
            // Hidden entirely when it would say "all of it", because a full bar
            // is a bar that means nothing and still asks to be read.
            extent.visibility = if (fraction >= 1f) View.INVISIBLE else View.VISIBLE
            shown.layoutParams = shown.layoutParams.apply {
                width = (extent.width * fraction).toInt()
            }
            shown.requestLayout()
        }
    }

    private companion object {
        const val COLUMNS = 2

        // Artboard units, from `AllWindows.dc.html`.
        const val CELL_HEIGHT = 52f
        const val COLUMN_GAP = 8f
        const val ROW_GAP = 6f
        const val ICON = 18f
        const val PADDING = 12f
        const val GAP = 10f
        const val EXTENT_HEIGHT = 3f
    }
}
