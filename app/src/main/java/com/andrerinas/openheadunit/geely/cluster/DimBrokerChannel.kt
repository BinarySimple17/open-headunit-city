package com.andrerinas.openheadunit.geely.cluster

import android.content.Context
import com.andrerinas.openheadunit.geely.hvac.HvacLog
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The cluster's own message broker, from a third-party seat (BL-004 fact 41, S015
 * round 2026-10-25): the firmware's `se.cevt.dimsupportservice` feeds the instrument
 * cluster from an MQTT broker on the loopback interface, and this channel is our socket
 * into it — the groundwork the DiPlay CarPlay port proved on a sibling ECARX unit.
 * Two independent, separately-switched halves:
 *  * **Observer (candidate И)** — subscribe `ihu/#` and echo every frame into the HVAC
 *    log through [BrokerFrameLogPolicy]. The stock publisher (the invisible competitor
 *    of the whole flicker hunt) and the projection-axis switches become observable
 *    WITHOUT root; if this head unit has no broker at all, the connect fails and that
 *    failure is itself the answer, logged once.
 *  * **Pin (candidate К)** — publish `ihu/info/phoneprojection`
 *    `{"statusType":"androidAutoMedia"}` while our card stands: the axis that decides
 *    whether the cluster's media card reads the phone projection or the head unit's own
 *    Bluetooth source. The late frame wins on this broker (DiPlay measured 2 ms flipping
 *    their card away), so the pin rides the same rare moments as a card publication —
 *    every track change, never a stream. `pinMediaProjection(false)` undoes OUR OWN pin
 *    with `noProjection` and nothing else — a process that never pinned publishes
 *    nothing on the way out.
 *
 * Traps the port paid for, kept here: the client id must differ from the service's own
 * «dimsupportservice» (the broker keys sessions by id — reusing it evicts the system
 * client and takes the DIM stack down); a keep-alive of 30 s with PINGREQ on read
 * timeout is the only way to notice a silently dead broker; and PUBACK on the reader
 * thread is the whole delivery evidence, because every publish is fire-and-forget from
 * the caller's thread (the bridge worker, which must never block on a socket).
 */
internal object DimBrokerChannel {

    private const val HOST = "127.0.0.1"
    private const val PORT = 1883
    private const val TOPIC_ALL = "ihu/#"
    private const val TOPIC_PROJECTION = "ihu/info/phoneprojection"

    /** `MediaHandler.updateMediaPhoneProjection(0)` — the media axis pinned to AA. */
    private const val STATUS_AA_MEDIA = "androidAutoMedia"

    /** `…(2)` — the undo; only ever sent for a pin this process published. */
    private const val STATUS_NONE = "noProjection"

    private const val KEEPALIVE_SECONDS = 30
    private const val CONNECT_TIMEOUT_MS = 1_500
    private const val READ_TIMEOUT_MS = 15_000
    private const val DEAD_AFTER_MS = 50_000L
    private const val RECONNECT_MS = 15_000L
    private const val STATS_MS = 60_000L

    @Volatile private var running = false
    @Volatile private var observe = false
    @Volatile private var socket: Socket? = null
    @Volatile private var pinned = false

    private val writeLock = Any()
    private var nextPacketId = 0
    private var policy = BrokerFrameLogPolicy()
    private var thread: Thread? = null

    /** Idempotent arm. [subscribeAll] re-arms the connection when it changes mid-drive. */
    @Synchronized
    fun ensureStarted(context: Context, subscribeAll: Boolean) {
        context.applicationContext // the channel is process-wide; the context only arms it
        if (running && observe == subscribeAll) return
        if (running) restartLocked()
        running = true
        observe = subscribeAll
        policy = BrokerFrameLogPolicy()
        pinned = false
        HvacLog.i("cluster.broker: armed (observe=$subscribeAll, $HOST:$PORT)")
        thread = Thread({ runLoop() }, "cluster-broker").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        closeSocket()
        thread = null
        HvacLog.i("cluster.broker: disarmed")
    }

    /**
     * The pin (candidate К). Called from the bridge worker on card publication and clear
     * — never blocks: the frame is written and the reader thread logs the PUBACK. A pin
     * that finds no socket is skipped (the next publication retries); `active=false`
     * publishes nothing unless this process actually pinned before.
     */
    fun pinMediaProjection(active: Boolean) {
        if (active) {
            if (publish("{\"statusType\":\"$STATUS_AA_MEDIA\"}")) pinned = true
            return
        }
        if (!pinned) return
        if (publish("{\"statusType\":\"$STATUS_NONE\"}")) pinned = false
    }

    private fun publish(payload: String): Boolean {
        val target = socket
        if (target == null) {
            HvacLog.w("cluster.broker: pin skipped — broker not connected")
            return false
        }
        val id = synchronized(writeLock) { nextPacketId = nextPacketId % 0xFFFF + 1; nextPacketId }
        val sent = synchronized(writeLock) {
            runCatching {
                target.getOutputStream().apply {
                    write(DimBrokerWire.publishPacket(id, TOPIC_PROJECTION, payload))
                    flush()
                }
            }.isSuccess
        }
        if (sent) HvacLog.i("cluster.broker: sent $TOPIC_PROJECTION $payload") else HvacLog.w("cluster.broker: pin write failed")
        return sent
    }

    /** The whole connection lifecycle lives on one thread; [stop] breaks it via the socket. */
    private fun runLoop() {
        var failureLogged = false
        while (running) {
            val input = try {
                connectAndSubscribe()
            } catch (e: IOException) {
                if (!failureLogged) {
                    failureLogged = true
                    HvacLog.w("cluster.broker: connect failed (${e.javaClass.simpleName}: ${e.message}) — retrying silently every ${RECONNECT_MS / 1000} s")
                }
                sleepUnlessStopped(RECONNECT_MS)
                continue
            }
            failureLogged = false
            readLoop(input)
            closeSocket()
            if (running) sleepUnlessStopped(RECONNECT_MS)
        }
    }

    /** CONNECT + (optional) SUBSCRIBE; returns the stream to read frames from. */
    private fun connectAndSubscribe(): InputStream {
        val connection = Socket()
        connection.connect(InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS)
        connection.soTimeout = READ_TIMEOUT_MS
        socket = connection
        val out = connection.getOutputStream()
        // The trap that kills the DIM stack: the broker evicts by client id, so ours
        // carries the pid — never the service's own «dimsupportservice».
        out.write(DimBrokerWire.connectPacket("openheadunit-${android.os.Process.myPid()}", KEEPALIVE_SECONDS))
        out.flush()
        val input = connection.getInputStream()
        val connack = DimBrokerWire.readPacket(input) ?: throw IOException("no CONNACK")
        if (connack.header and 0xF0 != DimBrokerWire.TYPE_CONNACK || connack.body.isEmpty() || connack.body.last().toInt() != 0) {
            throw IOException("CONNACK refused code=${connack.body.lastOrNull()?.toInt() ?: -1}")
        }
        if (observe) {
            out.write(DimBrokerWire.subscribePacket(1, TOPIC_ALL))
            out.flush()
            while (true) {
                val ack = DimBrokerWire.readPacket(input) ?: throw IOException("no SUBACK")
                if (ack.header and 0xF0 == DimBrokerWire.TYPE_SUBACK) break
            }
            HvacLog.i("cluster.broker: connected, subscribed $TOPIC_ALL")
        } else {
            HvacLog.i("cluster.broker: connected (observe off)")
        }
        return input
    }

    private fun readLoop(input: InputStream) {
        var lastInbound = System.currentTimeMillis()
        var lastStats = lastInbound
        while (running) {
            val frame = try {
                DimBrokerWire.readPacket(input)
            } catch (e: SocketTimeoutException) {
                val now = System.currentTimeMillis()
                if (now - lastInbound > DEAD_AFTER_MS) {
                    HvacLog.w("cluster.broker: broker silent for ${DEAD_AFTER_MS / 1000} s — reconnecting")
                    return
                }
                writePing()
                if (now - lastStats >= STATS_MS) {
                    lastStats = now
                    HvacLog.i("cluster.broker: frames ${policy.statsLine()}")
                }
                continue
            } catch (e: IOException) {
                return
            } ?: return // end of stream: the broker went away
            lastInbound = System.currentTimeMillis()
            when (frame.header and 0xF0) {
                DimBrokerWire.TYPE_PUBLISH -> onPublish(frame.header, frame.body)
                DimBrokerWire.TYPE_PUBACK -> {
                    val id = (frame.body.getOrNull(0)?.toInt()?.and(0xFF) ?: 0) shl 8 or
                        (frame.body.getOrNull(1)?.toInt()?.and(0xFF) ?: 0)
                    HvacLog.i("cluster.broker: puback id=$id — pin delivered")
                }
            }
        }
    }

    private fun onPublish(header: Int, body: ByteArray) {
        val qos = DimBrokerWire.publishQos(header)
        val parsed = DimBrokerWire.parsePublish(qos, body)
        if (parsed == null) {
            HvacLog.w("cluster.broker: malformed publish frame (${body.size} B)")
            return
        }
        val (topic, payload, packetId) = parsed
        if (qos >= 1 && packetId != 0) writeRaw(DimBrokerWire.pubackPacket(packetId))
        policy.onFrame(topic, payload)?.let { HvacLog.i("cluster.broker: $it") }
    }

    private fun writePing() = writeRaw(DimBrokerWire.pingreqPacket())

    private fun writeRaw(bytes: ByteArray) {
        val target = socket ?: return
        synchronized(writeLock) { runCatching { target.getOutputStream().apply { write(bytes); flush() } } }
    }

    private fun closeSocket() {
        val target = socket
        socket = null
        runCatching { target?.close() }
    }

    private fun restartLocked() {
        running = false
        closeSocket()
        thread?.let { t -> runCatching { t.join(1_000) } }
        thread = null
    }

    private fun sleepUnlessStopped(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (running && System.currentTimeMillis() < until) Thread.sleep(500)
    }
}
