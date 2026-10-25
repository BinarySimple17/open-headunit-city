package com.andrerinas.openheadunit.geely.cluster

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * MQTT 3.1.1 wire framing for the cluster's own message broker (BL-004 fact 41, S015
 * round 2026-10-25). The instrument cluster is fed by the firmware's
 * `se.cevt.dimsupportservice` — a Paho client of a broker on 127.0.0.1:1883 — and a
 * third-party app on the same head unit can speak to that broker over a plain loopback
 * socket: subscribe for observation, publish for control (proven on a sibling ECARX
 * unit by the DiPlay CarPlay port, whose bytecode dump is where this contract comes
 * from). Traps pinned by the same port: the client id must never be the service's own
 * «dimsupportservice» — the broker keys sessions by it and would evict the system
 * client, taking the whole DIM stack down; and every DIM-shaped call is void, so the
 * QoS-1 PUBACK is the only delivery evidence a publisher can get.
 *
 * Pure JVM — no android import, no socket here: [DimBrokerChannel] owns the connection;
 * this object only turns intents into bytes and bytes back into frames.
 */
internal object DimBrokerWire {

    /** MQTT 3.1.1 = protocol level 4; the broker rejects anything else. */
    private const val PROTOCOL_LEVEL = 4

    /** Clean session, no will, no credentials — an observer holds no state worth keeping. */
    private const val CONNECT_FLAGS_CLEAN_SESSION = 0x02

    /** Control-packet first bytes this stack ever sees. */
    const val TYPE_CONNACK = 0x20
    const val TYPE_PUBLISH = 0x30
    const val TYPE_PUBACK = 0x40
    const val TYPE_SUBACK = 0x90
    const val TYPE_PINGRESP = 0xD0

    data class Frame(val header: Int, val body: ByteArray)

    /** CONNECT: clean session, the given keep-alive, one client id. */
    fun connectPacket(clientId: String, keepAliveSeconds: Int): ByteArray {
        val body = ByteArrayOutputStream()
        mqttString(body, "MQTT")
        body.write(PROTOCOL_LEVEL)
        body.write(CONNECT_FLAGS_CLEAN_SESSION)
        body.write(keepAliveSeconds shr 8 and 0xFF)
        body.write(keepAliveSeconds and 0xFF)
        mqttString(body, clientId)
        return frame(0x10, body.toByteArray())
    }

    /** SUBSCRIBE, QoS 0 requested — observation needs no broker retries. */
    fun subscribePacket(packetId: Int, topicFilter: String): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(packetId shr 8 and 0xFF)
        body.write(packetId and 0xFF)
        mqttString(body, topicFilter)
        body.write(0)
        return frame(0x82, body.toByteArray())
    }

    /** PUBLISH with QoS 1: the broker answers PUBACK — the only delivery evidence. */
    fun publishPacket(packetId: Int, topic: String, payload: String): ByteArray {
        val body = ByteArrayOutputStream()
        mqttString(body, topic)
        body.write(packetId shr 8 and 0xFF)
        body.write(packetId and 0xFF)
        val bytes = payload.toByteArray(Charsets.UTF_8)
        body.write(bytes, 0, bytes.size)
        return frame(0x32, body.toByteArray())
    }

    /** PUBACK for an inbound QoS-1 PUBLISH — without it the broker re-sends the frame. */
    fun pubackPacket(packetId: Int): ByteArray = byteArrayOf(
        TYPE_PUBACK.toByte(), 2, (packetId shr 8 and 0xFF).toByte(), (packetId and 0xFF).toByte(),
    )

    fun pingreqPacket(): ByteArray = byteArrayOf(0xC0.toByte(), 0)

    /**
     * Reads one control packet, or null at end of stream. The remaining-length varint
     * (up to 4 digits) is decoded by hand — no dependency, and no frame on this broker
     * ever needs the fourth digit (256 MB).
     */
    fun readPacket(input: InputStream): Frame? {
        val header = input.read()
        if (header < 0) return null
        var multiplier = 1
        var length = 0
        while (true) {
            val digit = input.read()
            if (digit < 0) return null
            length += (digit and 0x7F) * multiplier
            if (digit and 0x80 == 0) break
            multiplier *= 128
            if (multiplier > 128 * 128 * 128) return null
        }
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            if (count < 0) return null
            read += count
        }
        return Frame(header, body)
    }

    /** QoS bits of a PUBLISH header byte. */
    fun publishQos(header: Int): Int = header shr 1 and 0x03

    /**
     * One inbound PUBLISH body -> topic + payload (+ its packet id when QoS ≥ 1, else 0).
     * Null on a body too short to even carry the topic — the caller logs the frame as
     * junk; a malformed frame must never crash the reader.
     */
    fun parsePublish(qos: Int, body: ByteArray): Triple<String, String, Int>? {
        if (body.size < 2) return null
        val topicLength = (body[0].toInt() and 0xFF shl 8) or (body[1].toInt() and 0xFF)
        var at = 2
        if (topicLength > body.size - at) return null
        val topic = String(body, at, topicLength, Charsets.UTF_8)
        at += topicLength
        val packetId = if (qos >= 1) {
            if (body.size < at + 2) return null
            val id = (body[at].toInt() and 0xFF shl 8) or (body[at + 1].toInt() and 0xFF)
            at += 2
            id
        } else {
            0
        }
        val payload = if (at < body.size) String(body, at, body.size - at, Charsets.UTF_8) else ""
        return Triple(topic, payload, packetId)
    }

    private fun frame(type: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(type)
        var remaining = body.size
        do {
            var digit = remaining % 128
            remaining /= 128
            if (remaining > 0) digit = digit or 0x80
            out.write(digit)
        } while (remaining > 0)
        out.write(body, 0, body.size)
        return out.toByteArray()
    }

    private fun mqttString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.write((bytes.size shr 8) and 0xFF)
        out.write(bytes.size and 0xFF)
        out.write(bytes, 0, bytes.size)
    }
}
