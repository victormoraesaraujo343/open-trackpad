package org.opentrackpad.client

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The v0.1 client: one full-screen touch surface and a line of status text.
 *
 * Deliberately plain. The rails, radial menu and themes in the UI spec belong to
 * v0.2, after the native trackpad has been proven on real hardware.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var surface: TouchSurfaceView
    private lateinit var status: TextView
    private lateinit var connection: HostConnection
    private lateinit var screen: ScreenCare

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surface = findViewById(R.id.touch_surface)
        status = findViewById(R.id.status)

        connection = HostConnection(onState = ::showState)
        surface.onFrame = ::onFrame
        surface.onSurfaceSize = ::onSurfaceSize

        // The status line is the only thing here that stays put. When the
        // interface grows, whatever else sits still goes in this call too.
        screen = ScreenCare(window)
        screen.protect(status)

        // A trackpad that goes to sleep under your fingers is useless. It does
        // not have to stay bright, though; ScreenCare handles that.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()
    }

    private var started = false

    private fun onSurfaceSize(metrics: SurfaceMetrics) {
        if (started) {
            connection.surfaceResized(metrics)
        } else {
            started = true
            connection.start(metrics)
        }
    }

    private fun onFrame(frame: TouchFrame) {
        screen.onActivity()
        connection.send(frame)
    }

    override fun onResume() {
        super.onResume()
        screen.resume()
    }

    override fun onPause() {
        screen.pause()
        super.onPause()
    }

    override fun onDestroy() {
        connection.stop()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // System bars come back after a system gesture or a dialog; put them
        // away again so they never steal touches from the surface.
        if (hasFocus) goImmersive()
    }

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showState(state: ConnectionState, detail: String?) {
        val text = when (state) {
            ConnectionState.DISCONNECTED -> getString(R.string.status_disconnected)
            ConnectionState.CONNECTING -> getString(R.string.status_connecting)
            ConnectionState.CONNECTED -> getString(R.string.status_connected)
            ConnectionState.RECONNECTING -> getString(R.string.status_reconnecting)
            ConnectionState.ERROR -> getString(R.string.status_error)
        }
        val color = when (state) {
            ConnectionState.CONNECTED -> R.color.status_connected
            ConnectionState.ERROR -> R.color.status_error
            else -> R.color.status_pending
        }
        status.setTextColor(getColor(color))

        // Never claim a latency figure: the two clocks have no shared origin.
        // When there is no host, say what to do about it.
        status.text = when (state) {
            ConnectionState.CONNECTED -> text
            ConnectionState.ERROR -> "$text — ${getString(R.string.setup_hint)}"
            else -> text
        }
        surface.visibility = View.VISIBLE
    }
}
