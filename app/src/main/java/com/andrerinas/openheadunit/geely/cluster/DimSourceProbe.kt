package com.andrerinas.openheadunit.geely.cluster

import com.andrerinas.openheadunit.geely.hvac.HvacLog
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Read-only diagnostics of the cluster's media source (S015): the card flicker "ours with
 * the cover ↔ the stock BT card" is switched by the firmware itself, and the only surface
 * the working Button reference also drives on this firmware family is the mediaInteraction
 * we already hold — a callback proxy registered through the set/register/add call (whose
 * single argument IS the interaction callback interface), plus a per-tick read-back of
 * `getPlaybackInfo()` covering the fallback source getters. Every verdict is a line in the
 * detailed HVAC-лог; NOTHING is published, no card is touched — answering
 * `onUpdateMediaStatusRequest` with a re-publish or yielding the slot (Button's "yield"
 * model) are separate iterations, each behind its own planner decision (D-010/D-011 stay).
 *
 * Registration is attempted once per channel ([release] re-arms it) and is guarded end to
 * end: the probe can never break the card. The firmware calls the handler on binder
 * threads — it only builds a short string and hands it to [onEvent]; the bridge marshals
 * it onto its worker thread (invoke() must not block or do IO there — ANR trap).
 */
internal object DimSourceProbe {

    /** The object-arg event family Button catches; each carries a card, read reflexively. */
    internal val OBJECT_EVENTS = setOf(
        "onMediaSourceSelected", "onSourceChanged", "onMediaSourceChanged", "onMediaSelected",
    )

    /** Once per channel: the registration is a firmware-visible state change. */
    @Volatile private var armed = false
    private val lock = Any()

    /** Worker-thread state: the read-back dedup and the once-per-reason failure marker. */
    @Volatile private var lastAnnounced = ""
    @Volatile private var lastFailure = ""

    /**
     * Registers the callback proxy on the connected mediaInteraction — attempts ONCE per
     * channel ([release] resets); the outcome (armed / surface absent / failed) is logged
     * exactly once. Must be called from the bridge's worker thread.
     */
    fun armOnce(media: Any, onEvent: (String) -> Unit) {
        if (armed) return
        synchronized(lock) {
            if (armed) return
            val method = findRegistrationMethod(media.javaClass)
            if (method == null) {
                // The surface is absent on this firmware: say so once, retrying would just
                // spam the log with the same answer.
                HvacLog.i("cluster.source: callback registration not found — no set/register/add method with an interface argument")
                armed = true
                return
            }
            try {
                val callbackInterface = method.parameterTypes[0]
                val proxy = Proxy.newProxyInstance(
                    callbackInterface.classLoader,
                    arrayOf(callbackInterface),
                    CallbackHandler(onEvent),
                )
                method.invoke(media, proxy)
                armed = true
                HvacLog.i("cluster.source: callback armed via ${method.name} (${callbackInterface.name})")
            } catch (e: Throwable) {
                // One verdict per channel: a rejecting firmware would answer the same way
                // every publish, so stay unarmed WITHOUT re-asking (events stay unseen, the
                // card lives on); release() re-arms on the next gate cycle.
                armed = true
                HvacLog.e("cluster.source: register via ${method.name} failed — no retry this channel", e.cause ?: e)
            }
        }
    }

    /** The channel went away (gates off, mock / not a Geely build): forget everything so the
     *  next arm registers fresh against the then-current binder object. */
    fun release() {
        synchronized(lock) { armed = false }
        lastAnnounced = ""
        lastFailure = ""
    }

    /** Per-tick read-back (called from the bridge worker): the card the cluster holds RIGHT
     *  NOW — `getPlaybackInfo()` with the Button fallback getters; logged on change only. */
    fun readBack(media: Any) {
        val card = grabCard(media)
        if (card != null) {
            describeCard(card)?.let { announce(it) }
                ?: noteFailure("getPlaybackInfo object answers no source getters")
            return
        }
        fallbackSource(media)?.let { announce(it) }
            ?: noteFailure("getPlaybackInfo and the source getters are both missing")
    }

    /**
     * The registration call, chosen exactly as Button does: the decoded name first
     * (media-interaction callback, set/register/add family, none of unset/remove), the
     * shape scan only as fallback — the scan alone races the unspecified Class.methods
     * order and can arm a foreign listener while claiming ours.
     */
    internal fun findRegistrationMethod(mediaClass: Class<*>): Method? {
        val byName = mediaClass.methods.firstOrNull { method ->
            val name = method.name.lowercase()
            method.parameterTypes.size == 1 && method.parameterTypes[0].isInterface &&
                name.contains("mediainteraction") && name.contains("callback") &&
                !name.contains("unset") && !name.contains("remove")
        }
        if (byName != null) return byName
        return mediaClass.methods.firstOrNull { method ->
            val name = method.name.lowercase()
            method.parameterTypes.size == 1 &&
                method.parameterTypes[0].isInterface &&
                (name.contains("set") || name.contains("register") || name.contains("add")) &&
                !name.contains("unset") && !name.contains("remove")
        }
    }

    /**
     * One firmware event name + arguments -> one log line. The known names get the exact
     * phrasing the round expects (`cluster.source: selected N`, `... status request`), the
     * object events unfold the card's `getSourceType()`, and an UNKNOWN name is still
     * logged with its argument classes — an unknown event is worth more than silence.
     */
    internal fun describeEvent(name: String, args: Array<out Any?>?): String = when {
        name == "onUpdateMediaStatusRequest" && args.isNullOrEmpty() ->
            "cluster.source: status request"
        name == "onSourceSelected" && args?.size == 1 && args[0] is Int ->
            "cluster.source: selected ${args[0]}"
        name in OBJECT_EVENTS && args?.size == 1 && args[0] != null -> {
            val card = args[0]!!
            "cluster.source: $name type=${intOf(card, "getSourceType") ?: "?"} " +
                "(${card.javaClass.simpleName})"
        }
        else -> "cluster.source: event $name(${args?.joinToString(", ") { it?.javaClass?.simpleName ?: "null" }})"
    }

    /**
     * The read-back descriptor of a pulled card: `uuid=… type=N (title)`; an object that
     * answers neither uuid nor type is not something we can interpret as a card.
     */
    internal fun describeCard(card: Any): String? {
        val uuid = stringOf(card, "getUUID")
        val type = intOf(card, "getSourceType")
        if (type == null && uuid == null) return null
        val head = "uuid=${uuid ?: "none"} type=${type ?: "?"}"
        val title = stringOf(card, "getTitle")
        return if (title != null) "$head ($title)" else head
    }

    /**
     * The binder callback handler: pure string building on the binder thread, object
     * methods answered safely (a wrong answer here kills the whole registration round).
     * All interface methods answer "void" — the firmware callbacks return nothing useful.
     */
    internal class CallbackHandler(
        private val sink: (String) -> Unit,
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? =
            if (method.declaringClass == Any::class.java) anyMethod(proxy, method, args)
            else {
                sink(describeEvent(method.name, args))
                defaultAnswer(method.returnType)
            }

        private fun anyMethod(proxy: Any, method: Method, args: Array<out Any?>?): Any = when (method.name) {
            "toString" -> "DimInteractionCallback"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> throw IllegalStateException("unexpected Object method: ${method.name}")
        }

        private fun defaultAnswer(type: Class<*>): Any? = when {
            type == java.lang.Void.TYPE || type == Void::class.java -> null
            type == java.lang.Boolean.TYPE || type == java.lang.Boolean::class.java -> false
            type == java.lang.Integer.TYPE || type == java.lang.Integer::class.java -> 0
            type == java.lang.Long.TYPE || type == java.lang.Long::class.java -> 0L
            else -> null
        }
    }

    private fun grabCard(media: Any): Any? = runCatching {
        media.javaClass.methods
            .firstOrNull { it.name == "getPlaybackInfo" && it.parameterTypes.isEmpty() }
            ?.invoke(media)
    }.getOrNull()

    /** Button's fallback getters when no card object comes back: the interaction itself
     *  answers the current source directly. */
    private fun fallbackSource(media: Any): String? {
        for (name in listOf(
            "getCurrentSourceType", "getSourceType", "getSelectedSourceType",
            "getCurrentSource", "getSelectedSource", "getSourceSelected",
        )) {
            val value = runCatching {
                media.javaClass.methods
                    .firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
                    ?.invoke(media)
            }.getOrNull() ?: continue
            val type = when (value) {
                is Int -> value
                else -> intOf(value, "getSourceType")
            } ?: continue
            return "type=$type"
        }
        // Last button fallback: any no-arg playback+info method whose result answers a
        // source type.
        val lastChance = runCatching {
            media.javaClass.methods
                .firstOrNull {
                    it.parameterTypes.isEmpty() && it.name.lowercase().contains("playback") &&
                        it.name.lowercase().contains("info")
                }
                ?.let { method -> runCatching { method.invoke(media) }.getOrNull() }
                ?.let { intOf(it, "getSourceType") }
        }.getOrNull()
        return lastChance?.let { "type=$it" }
    }

    private fun announce(descriptor: String) {
        if (descriptor == lastAnnounced) return
        lastAnnounced = descriptor
        lastFailure = ""
        HvacLog.i("cluster.source: $descriptor")
    }

    private fun noteFailure(reason: String) {
        if (reason == lastFailure) return
        lastFailure = reason
        // Past failures repeat every tick until fixed — dedup them to once per reason.
        HvacLog.d("cluster.source: read-back unavailable ($reason)")
    }

    /** A getter by name on ANY object (never a cast — trap #2: our own proxy may come back
     *  or an unknown parcel class): a miss or a type mismatch degrades to null. */
    private fun intOf(target: Any, name: String): Int? = runCatching {
        target.javaClass.methods
            .firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
            ?.invoke(target) as? Int
    }.getOrNull()

    private fun stringOf(target: Any, name: String): String? = runCatching {
        target.javaClass.methods
            .firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
            ?.invoke(target) as? String
    }.getOrNull()
}
