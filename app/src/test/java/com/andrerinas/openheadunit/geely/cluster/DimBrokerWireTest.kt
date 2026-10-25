package com.andrerinas.openheadunit.geely.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * The byte-level contract of the cluster broker channel (BL-004 fact 41): the frames are
 * hand-rolled MQTT 3.1.1, so every shape this stack puts on the wire is pinned here — a
 * byte that drifts reaches no broker and no DIM error ever says why. The field order and
 * the "late frame wins / PUBACK is the only evidence" semantics come from the DiPlay
 * CarPlay port's dump of the firmware's own service.
 */
class DimBrokerWireTest {

    @Test
    fun `connect packet is MQTT 3-1-1 clean session with keepalive and client id`() {
        val packet = DimBrokerWire.connectPacket("openheadunit-42", 30)
        val frame = DimBrokerWire.readPacket(ByteArrayInputStream(packet))
        assertNotNull(frame)
        assertEquals(0x10, frame!!.header)
        // body: 0004 "MQTT" | level 4 | flags 02 | keepalive 001E | 000F "openheadunit-42"
        assertEquals(4, frame.body[1].toInt())
        assertEquals("MQTT", String(frame.body, 2, 4, Charsets.UTF_8))
        assertEquals(4, frame.body[6].toInt())
        assertEquals(0x02, frame.body[7].toInt())
        assertEquals(30, (frame.body[8].toInt() shl 8) or frame.body[9].toInt())
        assertEquals("openheadunit-42", String(frame.body, 12, 15, Charsets.UTF_8))
    }

    @Test
    fun `subscribe packet carries the filter and requests qos 0`() {
        val packet = DimBrokerWire.subscribePacket(7, "ihu/#")
        val frame = DimBrokerWire.readPacket(ByteArrayInputStream(packet))!!
        assertEquals(0x82, frame.header)
        assertEquals(7, (frame.body[0].toInt() shl 8) or frame.body[1].toInt())
        // body: id(2) | len(2) | "ihu/#" | qos — the filter starts at offset 4.
        assertEquals("ihu/#", String(frame.body, 4, 5, Charsets.UTF_8))
        assertEquals("requested QoS 0 is the last byte", 0, frame.body[9].toInt())
    }

    @Test
    fun `a publish round-trips through the wire with qos 1 and a two-byte length`() {
        // 200+ payload bytes force the two-digit remaining-length varint — the boundary
        // a naive single-byte encoder silently corrupts.
        val payload = "{\"statusType\":\"androidAutoMedia\"}" + "x".repeat(200)
        val packet = DimBrokerWire.publishPacket(9, "ihu/info/phoneprojection", payload)
        val frame = DimBrokerWire.readPacket(ByteArrayInputStream(packet))!!
        assertEquals(0x32, frame.header)
        assertEquals(1, DimBrokerWire.publishQos(frame.header))
        val parsed = DimBrokerWire.parsePublish(1, frame.body)!!
        assertEquals("ihu/info/phoneprojection", parsed.first)
        assertEquals(payload, parsed.second)
        assertEquals(9, parsed.third)
    }

    @Test
    fun `a qos 0 publish body parses without a packet id`() {
        val body = byteArrayOf(0, 3) + "ihu".toByteArray() + "{}".toByteArray()
        val parsed = DimBrokerWire.parsePublish(0, body)!!
        assertEquals("ihu", parsed.first)
        assertEquals("{}", parsed.second)
        assertEquals(0, parsed.third)
    }

    @Test
    fun `a body too short for its topic is junk, not a crash`() {
        assertNull(DimBrokerWire.parsePublish(0, byteArrayOf(0, 9)))
        assertNull(DimBrokerWire.parsePublish(1, byteArrayOf(0, 0)))
        assertNull(DimBrokerWire.parsePublish(0, ByteArray(1)))
    }

    @Test
    fun `puback and pingreq are the two fixed shapes`() {
        val puback = DimBrokerWire.pubackPacket(5)
        assertEquals(0x40, puback[0].toInt() and 0xFF)
        assertEquals(2, puback[1].toInt())
        assertEquals(5, (puback[2].toInt() shl 8) or puback[3].toInt())
        val ping = DimBrokerWire.pingreqPacket()
        assertEquals(0xC0, ping[0].toInt() and 0xFF)
        assertEquals(0, ping[1].toInt())
    }

    @Test
    fun `end of stream reads as null`() {
        assertNull(DimBrokerWire.readPacket(ByteArrayInputStream(ByteArray(0))))
    }
}
