package com.andrerinas.openheadunit.geely.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The pure surface of the source probe (S015): registration-method selection follows the
 * Button criteria exactly (one argument that is an interface, name contains
 * set/register/add, none of unset/remove), the callback handler turns firmware events into
 * one-line strings and never crashes on Object methods, and the read-back descriptor reads
 * a pulled card by name. All firmware shapes are faked — the real interface is only known
 * inside the car.
 */
class DimSourceProbeTest {

    private class TestCard(id: String?, type: Int?, title: String?) {
        private val cardId: String? = id
        private val cardType: Int? = type
        private val cardTitle: String? = title
        fun getUUID(): String? = cardId
        fun getSourceType(): Int? = cardType
        fun getTitle(): String? = cardTitle
    }

    private class TestSourceOnly(type: Int) {
        private val cardType: Int = type
        fun getSourceType(): Int = cardType
    }

    private interface TestCallback {
        fun onSourceSelected(type: Int)
        fun onUpdateMediaStatusRequest()
        fun onMediaSourceSelected(info: Any)
        fun onFirmwareInventedEvent(what: String)
    }

    private interface TestMedia {
        fun setMediaInteractionCallback(callback: TestCallback)
        fun unsetMediaInteractionCallback(callback: TestCallback)
        fun removeMediaInteractionCallback(callback: TestCallback)
        fun addMediaInteractionCallback(callback: TestCallback, priority: Int)
        fun setVolume(level: Int)
    }

    private interface ForeignListener {
        fun onForeign(x: Int)
    }

    /** A foreign interface setter declared BEFORE the media callback: the shape scan alone
     *  would take whichever comes first in the unspecified Class.methods order. */
    private class TestForeignFirst {
        fun setForeignListener(listener: ForeignListener) {}
        fun setMediaInteractionCallback(callback: TestCallback) {}
    }

    private class TestScanOnly {
        fun setForeignListener(listener: ForeignListener) {}
    }

    @Test
    fun `the registration method is picked by the Button signature`() {
        val method = DimSourceProbe.findRegistrationMethod(TestMedia::class.java)
        assertEquals("setMediaInteractionCallback", method?.name)
    }

    @Test
    fun `the decoded name wins over a foreign setter regardless of method order`() {
        val method = DimSourceProbe.findRegistrationMethod(TestForeignFirst::class.java)
        assertEquals("setMediaInteractionCallback", method?.name)
    }

    @Test
    fun `without the decoded name the shape scan still finds a setter`() {
        val method = DimSourceProbe.findRegistrationMethod(TestScanOnly::class.java)
        assertEquals("setForeignListener", method?.name)
    }

    @Test
    fun `media without the surface answers no registration method`() {
        assertNull(DimSourceProbe.findRegistrationMethod(TestCard::class.java))
    }

    @Test
    fun `onSourceSelected logs its int argument`() {
        assertEquals(
            "cluster.source: selected 2",
            DimSourceProbe.describeEvent("onSourceSelected", arrayOf<Any?>(2)),
        )
    }

    @Test
    fun `onUpdateMediaStatusRequest is the status request`() {
        assertEquals(
            "cluster.source: status request",
            DimSourceProbe.describeEvent("onUpdateMediaStatusRequest", null),
        )
    }

    @Test
    fun `an object event unfolds the card's sourceType reflectively`() {
        val line = DimSourceProbe.describeEvent("onMediaSourceSelected", arrayOf<Any?>(TestCard("u", 6, "Song")))
        assertTrue(line.startsWith("cluster.source: onMediaSourceSelected"))
        assertTrue(line.contains("type=6"))
        assertTrue(line.contains("TestCard"))
    }

    @Test
    fun `an object event without a readable sourceType stays honest`() {
        val line = DimSourceProbe.describeEvent("onSourceChanged", arrayOf<Any?>("not a card object"))
        assertTrue(line.contains("onSourceChanged"))
        assertTrue(line.contains("type=?"))
    }

    @Test
    fun `an unknown event name is logged with its argument classes, not swallowed`() {
        val line = DimSourceProbe.describeEvent("onFirmwareInventedEvent", arrayOf<Any?>("what", null))
        assertTrue(line.startsWith("cluster.source: event onFirmwareInventedEvent"))
        assertTrue(line.contains("String, null"))
    }

    @Test
    fun `a read-back descriptor carries uuid type and title`() {
        assertEquals("uuid=abc type=6 (Song)", DimSourceProbe.describeCard(TestCard("abc", 6, "Song")))
    }

    @Test
    fun `a card without a title or uuid still describes the slot`() {
        assertEquals("uuid=none type=6", DimSourceProbe.describeCard(TestCard(null, 6, null)))
        assertEquals("uuid=u type=? (t)", DimSourceProbe.describeCard(TestCard("u", null, "t")))
    }

    @Test
    fun `an object without known getters is not a card`() {
        assertNull(DimSourceProbe.describeCard(Any()))
    }

    @Test
    fun `a type-only card object still yields a descriptor`() {
        assertEquals("uuid=none type=4", DimSourceProbe.describeCard(TestSourceOnly(4)))
    }

    @Test
    fun `the callback proxy logs events and answers Object methods safely`() {
        val lines = mutableListOf<String>()
        val handler = DimSourceProbe.CallbackHandler { lines.add(it) }
        val callback = Proxy.newProxyInstance(
            TestCallback::class.java.classLoader,
            arrayOf(TestCallback::class.java),
            handler,
        ) as TestCallback

        callback.onSourceSelected(4)
        callback.onUpdateMediaStatusRequest()
        callback.onMediaSourceSelected(TestCard("u", 6, "Song"))
        callback.onFirmwareInventedEvent("what")
        assertEquals(
            listOf(
                "cluster.source: selected 4",
                "cluster.source: status request",
                "cluster.source: onMediaSourceSelected type=6 (TestCard)",
                "cluster.source: event onFirmwareInventedEvent(String)",
            ),
            lines,
        )

        // Trap #1 (round 5): the native side may call these while logging our proxy.
        assertEquals("DimInteractionCallback", callback.toString())
        assertTrue(callback.equals(callback))
        assertFalse(callback.equals(Any()))
        callback.hashCode()
    }
}
