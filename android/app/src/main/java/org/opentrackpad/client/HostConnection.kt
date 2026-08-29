package org.opentrackpad.client

import android.os.Handler
import android.os.Looper
import java.io.BufferedWriter
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** What the user is told about the link to the computer. */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR,
}

/**
 * Carries touch frames to the host daemon over the adb-forwarded loopback
 * socket.
 *
 * Everything that can block — connecting, writing, waiting to retry — happens on
 * a dedicated thread. The UI thread only ever hands over a frame and returns.
 */
class HostConnection(
    private val host: String = "127.0.0.1",
    private val port: Int = 4242,
    private val onState: (ConnectionState, String?) -> Unit,
) {
    private companion object {
        const val CONNECT_TIMEOUT_MS = 2_000

        /** Backoff between reconnect attempts, capped so it stays responsive. */
        const val RETRY_MIN_MS = 250L
        const val RETRY_MAX_MS = 4_000L

    }

    private val main = Handler(Looper.getMainLooper())
    private val pending = FrameQueue()
    private val lock = Object()

    @Volatile private var running = false
    @Volatile private var surface: SurfaceMetrics? = null

    /** Set when the surface size changes, so the session restarts with a new HELLO. */
    @Volatile private var surfaceChanged = false

    private var worker: Thread? = null
    private var sequence = 0L

    fun start(metrics: SurfaceMetrics) {
        synchronized(lock) {
            surface = metrics
            if (running) {
                surfaceChanged = true
                lock.notifyAll()
                return
            }
            running = true
        }
        worker = Thread(::run, "opentrackpad-sender").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Reports a new touch surface size.
     *
     * The protocol sends dimensions once per session, so a resize means the
     * session has to be restarted rather than patched.
     */
    fun surfaceResized(metrics: SurfaceMetrics) {
        synchronized(lock) {
            if (metrics == surface) return
            surface = metrics
            surfaceChanged = true
            pending.clear()
            lock.notifyAll()
        }
    }

    fun stop() {
        synchronized(lock) {
            running = false
            lock.notifyAll()
        }
        worker?.join(500)
        worker = null
    }

    /**
     * Queues one snapshot. Returns immediately; never blocks the caller.
     */
    fun send(frame: TouchFrame) {
        synchronized(lock) {
            if (!running) return
            pending.add(frame)
            lock.notifyAll()
        }
    }

    private fun report(state: ConnectionState, detail: String? = null) {
        main.post { onState(state, detail) }
    }

    private fun run() {
        var backoff = RETRY_MIN_MS
        var everConnected = false

        while (isRunning()) {
            report(if (everConnected) ConnectionState.RECONNECTING else ConnectionState.CONNECTING)
            try {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    everConnected = true
                    backoff = RETRY_MIN_MS
                    report(ConnectionState.CONNECTED)
                    serve(socket)
                }
            } catch (error: IOException) {
                report(ConnectionState.ERROR, error.message)
            }

            if (!isRunning()) break
            waitBeforeRetry(backoff)
            backoff = (backoff * 2).coerceAtMost(RETRY_MAX_MS)
        }
        report(ConnectionState.DISCONNECTED)
    }

    /** Runs one session until the socket dies or the surface changes size. */
    private fun serve(socket: Socket) {
        val writer = socket.getOutputStream().bufferedWriter()
        val metrics: SurfaceMetrics
        synchronized(lock) {
            metrics = surface ?: return
            surfaceChanged = false
            // Anything queued belongs to the previous session's coordinate
            // space and sequence; start clean.
            pending.clear()
            sequence = 0
        }
        writer.write(
            Protocol.hello(
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.widthMicrometres,
                metrics.heightMicrometres,
            )
        )
        writer.write("\n")
        writer.flush()

        while (true) {
            val frame = nextFrame() ?: break
            sequence += 1
            writer.write(frame.encode(sequence))
            writer.write("\n")
            // Flushing per frame keeps latency down; these lines are tiny.
            writer.flush()
        }

        // Leave no finger behind: tell the host everything lifted before the
        // socket closes, so it never has to infer it from a disconnect.
        releaseAll(writer)
    }

    private fun releaseAll(writer: BufferedWriter) {
        try {
            sequence += 1
            writer.write(TouchFrame.empty(System.nanoTime()).encode(sequence))
            writer.write("\n")
            writer.flush()
        } catch (_: IOException) {
            // The socket is already gone; the host releases contacts itself
            // when a connection drops, so there is nothing left to do.
        }
    }

    /** Blocks until a frame is available, or returns null when the session ends. */
    private fun nextFrame(): TouchFrame? {
        synchronized(lock) {
            while (running && !surfaceChanged && pending.size == 0) {
                try {
                    lock.wait()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            if (!running || surfaceChanged) return null
            return pending.poll()
        }
    }

    private fun isRunning(): Boolean = synchronized(lock) { running }

    private fun waitBeforeRetry(millis: Long) {
        synchronized(lock) {
            if (!running) return
            try {
                lock.wait(millis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
