package org.opentrackpad.client

import android.os.Handler
import android.os.Looper
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** What the user is told about the link to the computer. */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,

    /**
     * Talking to a computer that has not been updated.
     *
     * The session is real and the trackpad works; the older host has no idea
     * what a rail is, so nothing that sends an action may be offered. Honest
     * about being half a product rather than pretending buttons work.
     */
    LIMITED,
    RECONNECTING,

    /** Nothing is listening: the bridge is down, or the computer is asleep. */
    ERROR,

    /**
     * Something else is already the trackpad.
     *
     * The host serves one client at a time, so the other version of this app —
     * or a second copy of this one — holds the session until it is closed. The
     * socket still connects, because the connection sits in the host's backlog
     * unread; the absence of a reply is the only sign.
     */
    BUSY,

    /** The computer answered, but not in a language this version speaks. */
    INCOMPATIBLE,
    ;

    /** Whether a shortcut pressed now would reach the computer. */
    val carriesActions: Boolean get() = this == CONNECTED
}

/**
 * Carries touch frames and shortcuts to the host daemon over the adb-forwarded
 * loopback socket.
 *
 * Everything that can block — connecting, writing, waiting to retry — happens on
 * a dedicated thread. The UI thread only ever hands over a frame and returns.
 */
class HostConnection(
    private val host: String = "127.0.0.1",
    private val port: Int = 4242,

    /** What to ask the host to tell us about. Empty asks for a trackpad only. */
    private val wanted: Set<String> = emptySet(),
    private val onState: (ConnectionState, String?) -> Unit,

    /** What the host agreed to serve, which may be less than [wanted]. */
    private val onGranted: (Set<String>) -> Unit = {},

    /** One line the host sent, on the main thread, already trimmed. */
    private val onLine: (String) -> Unit = {},
) {
    private companion object {
        const val CONNECT_TIMEOUT_MS = 2_000

        /**
         * How long to wait for the host to answer the handshake.
         *
         * A host that has read the line answers in the same breath, so this is
         * not really a latency budget: it is how long to wait before concluding
         * that nobody read it, which happens when another client already has
         * the session. Generous enough to survive a slow first frame, short
         * enough that the person is told rather than left looking at a dead
         * screen.
         */
        const val HANDSHAKE_TIMEOUT_MS = 1_500

        /**
         * How long to wait for the reader to notice the socket has gone.
         *
         * Short, because it is only tidiness: the read fails the instant the
         * socket closes. Joining at all is what stops a line from a finished
         * session being delivered during the next one.
         */
        const val READER_JOIN_MS = 250L

        /** Backoff between reconnect attempts, capped so it stays responsive. */
        const val RETRY_MIN_MS = 250L
        const val RETRY_MAX_MS = 4_000L

        /** Far more shortcuts than a hand can produce, so a wedged socket cannot pile them up. */
        const val MAX_PENDING_ACTIONS = 32
    }

    /**
     * A line the client sends that is neither touch nor a shortcut.
     *
     * A wrapper rather than a bare string so that nothing can queue arbitrary
     * text on the socket by accident. The only things that build one are the
     * request encoders in [Audio], which draw from a closed vocabulary.
     */
    @JvmInline
    value class Request(val line: String)

    /** How an attempt at a session ended, before any frame was sent. */
    private enum class Handshake {
        /** The host is ours and agreed. */
        ACCEPTED,

        /** The host closed the connection without a word: it speaks something else. */
        REFUSED,

        /** Nobody read the handshake. Another client has the session. */
        UNANSWERED,
    }

    private val main = Handler(Looper.getMainLooper())
    private val pending = FrameQueue()

    /// Actions ride the same socket but never queue behind movement: a button
    /// press has to feel immediate, and the host treats the two as unrelated.
    private val actions = ArrayDeque<Action>()

    /// Panel requests share the shortcut lane: both are somebody's finger on a
    /// control, and both would feel wrong queued behind pointer movement.
    private val requests = ArrayDeque<Request>()
    private val lock = Object()

    @Volatile private var running = false
    @Volatile private var surface: SurfaceMetrics? = null

    /** Set when the surface size changes, so the session restarts with a new HELLO. */
    @Volatile private var surfaceChanged = false

    private var worker: Thread? = null
    private var sequence = 0L

    /**
     * The language this session actually settled on.
     *
     * Not what we opened with: a host that refuses version 4 gets asked again in
     * version 3, and from then until the socket dies there are things the client
     * knows how to say that this host would hang up over.
     */
    @Volatile
    private var speaks = Protocol.VERSION

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
     * Queues a shortcut. Returns immediately; never blocks the caller.
     */
    fun send(action: Action) {
        synchronized(lock) {
            if (!running) return
            if (actions.size >= MAX_PENDING_ACTIONS) {
                // Only a bug produces this many, and the host drops floods
                // anyway. Better to lose them here than to grow without bound.
                return
            }
            actions.addLast(action)
            lock.notifyAll()
        }
    }

    /**
     * Queues one request to a panel's domain. Never blocks the caller.
     */
    fun send(request: Request) {
        synchronized(lock) {
            if (!running) return
            // A dragged fader sends a level per frame, which is real; a stuck
            // one would pile up the same way a wedged shortcut would.
            if (requests.size >= MAX_PENDING_ACTIONS) return
            requests.addLast(request)
            lock.notifyAll()
        }
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

        // Which version to open with. Lowered once, and only after a host has
        // actually refused ours; raised again when the host disappears, since
        // whatever comes back may well be a newer build.
        var version = Protocol.VERSION

        while (isRunning()) {
            report(if (everConnected) ConnectionState.RECONNECTING else ConnectionState.CONNECTING)
            var retryAtOnce = false
            try {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    val writer = socket.getOutputStream().bufferedWriter()
                    // A connected socket is not yet a session: the host may be
                    // serving somebody else, and ours is only sitting in its
                    // backlog. Only a completed handshake counts as connected,
                    // which is also what stops a failed one from being reported
                    // afterwards as "reconnecting" to something we never had.
                    speaks = version
                    when (openSession(socket, writer, version)) {
                        Handshake.ACCEPTED -> {
                            everConnected = true
                            backoff = RETRY_MIN_MS
                            // The detail carries which version was agreed, so
                            // the settings screen can say it rather than assume
                            // the newest one was the one that worked.
                            report(
                                if (version == Protocol.VERSION) ConnectionState.CONNECTED
                                else ConnectionState.LIMITED,
                                version,
                            )
                            // Only version 4 says anything back, so only
                            // version 4 is listened to.
                            val reader =
                                if (version == Protocol.VERSION) listen(socket) else null
                            try {
                                pump(writer)
                            } finally {
                                // The socket closing is what stops the reader;
                                // joining afterwards keeps a dead session's
                                // last line from arriving during the next one.
                                reader?.join(READER_JOIN_MS)
                            }
                        }

                        Handshake.UNANSWERED -> report(ConnectionState.BUSY)

                        Handshake.REFUSED ->
                            if (version == Protocol.VERSION) {
                                // The host is older. It said so by hanging up
                                // rather than replying, so there is nothing to
                                // wait for: come straight back one version down.
                                version = Protocol.FALLBACK_VERSION
                                retryAtOnce = true
                            } else {
                                // Refused even the older handshake. This is not
                                // an OpenTrackpad host at all.
                                report(ConnectionState.INCOMPATIBLE)
                            }
                    }
                }
            } catch (error: IOException) {
                // Nothing is listening. Whatever appears next may be a newer
                // build, so stop assuming the computer is behind.
                version = Protocol.VERSION
                report(ConnectionState.ERROR, error.message)
            }

            if (!isRunning()) break
            if (retryAtOnce) continue
            waitBeforeRetry(backoff)
            backoff = (backoff * 2).coerceAtMost(RETRY_MAX_MS)
        }
        report(ConnectionState.DISCONNECTED)
    }

    /**
     * Sends the handshake and finds out what the other end is.
     *
     * Only version 4 is answered, so only version 4 waits. The fallback
     * handshake is sent and believed, exactly as every version before this one
     * did — which also means a busy host cannot be told apart from a working
     * one while talking to an older computer. That is the older protocol's
     * limit, not a gap here.
     */
    private fun openSession(
        socket: Socket,
        writer: BufferedWriter,
        version: String,
    ): Handshake {
        val metrics = synchronized(lock) { surface } ?: return Handshake.UNANSWERED
        writer.write(
            Protocol.hello(
                width = metrics.widthPixels,
                height = metrics.heightPixels,
                widthMicrometres = metrics.widthMicrometres,
                heightMicrometres = metrics.heightMicrometres,
                version = version,
                capabilities = if (version == Protocol.VERSION) {
                    Protocol.capabilities(wanted)
                } else {
                    // The field does not exist before version 4, and an older
                    // host treats a trailing one as fatal.
                    null
                },
            )
        )
        writer.write("\n")
        writer.flush()

        if (version != Protocol.VERSION) return Handshake.ACCEPTED

        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        val reply = try {
            readLine(socket.getInputStream())
        } catch (_: SocketTimeoutException) {
            // Read, but never answered: our connection is in the backlog behind
            // whoever holds the session.
            return Handshake.UNANSWERED
        } finally {
            socket.soTimeout = 0
        }
        // A closed connection with no reply is the version check failing, which
        // the host does before anything else.
        if (!Protocol.welcomeIsOurs(reply)) return Handshake.REFUSED
        val granted = Protocol.welcomeCapabilities(reply)
        main.post { onGranted(granted) }
        return Handshake.ACCEPTED
    }

    /**
     * Reads one line, byte at a time.
     *
     * Deliberately not a `BufferedReader`: that would read ahead into whatever
     * the host sends next, and those bytes would be lost when the reader is
     * dropped. One line is a couple of dozen bytes and this happens once per
     * session, so the syscalls do not matter.
     *
     * Returns null when the host closed the connection instead of replying.
     */
    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (line.length < Protocol.MAX_LINE_BYTES) {
            val byte = input.read()
            if (byte < 0) return null
            if (byte == '\n'.code) return line.toString()
            if (byte != '\r'.code) line.append(byte.toChar())
        }
        return line.toString()
    }

    /**
     * Reads whatever the host says, on a thread of its own.
     *
     * A separate thread rather than a poll inside the writer, because the
     * writer spends its life blocked waiting for the next frame and a read
     * folded into it would either add latency to touch or never happen. The two
     * directions share only the socket.
     *
     * It ends by itself: closing the socket makes the read fail, which is how
     * every session finishes.
     */
    private fun listen(socket: Socket): Thread =
        Thread({
            try {
                val input = socket.getInputStream()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) continue
                    main.post { onLine(line) }
                }
            } catch (_: IOException) {
                // The session ended. The writer already knows, and the state it
                // reports is the one the person should see.
            }
        }, "opentrackpad-reader").apply {
            isDaemon = true
            start()
        }

    /** Runs one session until the socket dies or the surface changes size. */
    private fun pump(writer: BufferedWriter) {
        synchronized(lock) {
            surfaceChanged = false
            // Anything queued belongs to the previous session's coordinate
            // space and sequence; start clean.
            pending.clear()
            actions.clear()
            requests.clear()
            sequence = 0
        }
        while (true) {
            when (val next = nextMessage() ?: break) {
                is Outgoing.Shortcut -> {
                    // Dropped here rather than refused at the button, because
                    // this is the only place that knows which language the
                    // session settled on. Silently: a version 3 session already
                    // says so on its own card and in settings, and a second
                    // complaint per press would say nothing the first did not.
                    if (next.action.afterVersionThree && speaks != Protocol.VERSION) continue
                    writer.write(next.action.encode(++sequence))
                }
                // Requests carry their own numbering, which the host echoes
                // back only when refusing, so it is not the frame sequence.
                is Outgoing.Ask -> writer.write(next.request.line)
                is Outgoing.Snapshot -> writer.write(next.frame.encode(++sequence))
            }
            writer.write("\n")
            // Flushing per message keeps latency down; these lines are tiny.
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

    /** One thing to write: a shortcut, or a snapshot of the fingers. */
    private sealed interface Outgoing {
        data class Shortcut(val action: Action) : Outgoing
        data class Ask(val request: Request) : Outgoing
        data class Snapshot(val frame: TouchFrame) : Outgoing
    }

    /**
     * Blocks until there is something to write, or returns null when the
     * session ends.
     *
     * Shortcuts go first. They are rare and a button has to feel immediate,
     * while a snapshot that waits a few milliseconds is superseded by the next
     * one anyway.
     */
    private fun nextMessage(): Outgoing? {
        synchronized(lock) {
            while (running && !surfaceChanged &&
                actions.isEmpty() && requests.isEmpty() && pending.size == 0
            ) {
                try {
                    lock.wait()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            if (!running || surfaceChanged) return null
            actions.removeFirstOrNull()?.let { return Outgoing.Shortcut(it) }
            requests.removeFirstOrNull()?.let { return Outgoing.Ask(it) }
            return pending.poll()?.let { Outgoing.Snapshot(it) }
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
