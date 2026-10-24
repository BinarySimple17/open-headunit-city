package com.binarysimple.hvacwidget.car

import android.content.Context
import android.os.SystemClock
import com.binarysimple.hvacwidget.WidgetLog
import com.binarysimple.hvacwidget.functions.WidgetFunctions
import com.binarysimple.hvacwidget.functions.WidgetSettings
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * The widget's car backend (D-007/D-008): the ECARX adaptapi, reached reflectively — a
 * hand-synced copy of the :app panel's EcarxCarPort (rounds 3–6 proved writes through THIS
 * entrance are accepted by the firmware while the android.car write path is rejected; the
 * standalone widget carries its own copy, no class of :app is referenced).
 *
 * The api is not part of the regular SDK classpath, so every class, method and value travels
 * through reflection, matching the firmware's real signatures — `Car.create(Context)` →
 * `ICar` → `getICarFunction()` → `ICarFunction`, `setFunctionValue(int,int,int) → boolean`,
 * `getFunctionValue(int,int) → int`, `isFunctionSupported(int,int) → FunctionStatus`.
 * Method lookup adapts to the parameter boxing the firmware carries: candidates are found
 * by name + arity, ints are boxed or unboxed per the resolved parameter types.
 *
 * The global zone is [WidgetFunctions.GLOBAL_AREA] (Integer.MIN_VALUE) passed through AS IS —
 * that is the adaptapi convention.
 *
 * NOT thread-safe against concurrent [connect]s; call once from the poll service's or the
 * command runner's worker before any traffic. get/set are safe from any thread once connected.
 */
object EcarxWidgetPort : CarFunctionPort {

    private const val CAR_CLASS = "com.ecarx.xui.adaptapi.car.Car"

    @Volatile private var carFunction: Any? = null
    @Volatile private var setFunctionValue: Method? = null
    @Volatile private var getFunctionValue: Method? = null
    @Volatile private var isFunctionSupported: Method? = null

    override val available: Boolean get() = carFunction != null

    /**
     * Blocking connect (`Car.create` binds the firmware's car service); must run off the
     * main thread. Returns false — never throws — with the reason in [WidgetLog]. The very
     * first failure mode is `ClassNotFoundException`: not a Geely/ECARX build.
     */
    fun connect(context: Context): Boolean {
        if (available) return true
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            val carClass = Class.forName(CAR_CLASS)
            val create = carClass.methods.firstOrNull {
                Modifier.isStatic(it.modifiers) &&
                    it.name == "create" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Context::class.java
            } ?: return fail("adaptapi: $CAR_CLASS.create(Context) not found")
            val car = create.invoke(null, context.applicationContext)
                ?: return fail("adaptapi: Car.create(context) returned null after ${ms(startedAt)} ms")

            val getFn = car.javaClass.methods.firstOrNull {
                it.name == "getICarFunction" && it.parameterTypes.isEmpty()
            } ?: return fail("adaptapi: ICar.getICarFunction() not found")
            val fn = getFn.invoke(car)
                ?: return fail("adaptapi: getICarFunction() returned null after ${ms(startedAt)} ms")

            val setM = methodByShape(fn.javaClass, "setFunctionValue", arity = 3)
                ?: return fail("adaptapi: ICarFunction.setFunctionValue(id, area, value) not found")
            val getM = methodByShape(fn.javaClass, "getFunctionValue", arity = 2)
                ?: return fail("adaptapi: ICarFunction.getFunctionValue(id, area) not found")
            val supportedM = methodByShape(fn.javaClass, "isFunctionSupported", arity = 2)

            setFunctionValue = setM
            getFunctionValue = getM
            isFunctionSupported = supportedM
            carFunction = fn
            WidgetLog.i("ecarx.adaptapi: ICarFunction ready in ${ms(startedAt)} ms " +
                "(set=${setM.parameterTypes.contentToString()}, probe=${supportedM != null})")
            supportedM?.let { probeTrackedFunctions(fn, it, context) }
            true
        } catch (_: ClassNotFoundException) {
            fail("adaptapi: $CAR_CLASS not on the classpath — not a Geely/ECARX build")
        } catch (e: InvocationTargetException) {
            fail("adaptapi: init threw", e.cause ?: e)
        } catch (e: Throwable) {
            fail("adaptapi: init failed", e)
        }
    }

    /**
     * Finds the method by name and parameter count, whatever boxing this firmware carries —
     * the invoke path adapts per-parameter. The primitive-int form is preferred so the
     * invoke path boxes nothing extra.
     */
    private fun methodByShape(cls: Class<*>, name: String, arity: Int): Method? =
        cls.methods.filter { it.name == name && it.parameterTypes.size == arity }
            .sortedByDescending { it.parameterTypes.count { p -> p == Integer.TYPE } }
            .firstOrNull()

    /** Boxes/unboxes ints per the resolved parameter types. */
    private fun adaptArgs(method: Method, ints: IntArray): Array<Any?> =
        Array(method.parameterTypes.size) { i ->
            if (method.parameterTypes[i] == Integer.TYPE) ints[i] else Integer.valueOf(ints[i])
        }

    override fun getInt(propertyId: Int, areaId: Int): Int? = read(propertyId, areaId, verbose = true)

    /** Polling path: no per-call success line, or a read every few seconds would be the whole log. */
    override fun getIntQuiet(propertyId: Int, areaId: Int): Int? = read(propertyId, areaId, verbose = false)

    /** Quiet-path failures already reported per property; a repeat stays silent until a success. */
    private val quietFailuresReported = mutableSetOf<String>()

    private fun read(propertyId: Int, areaId: Int, verbose: Boolean): Int? {
        val fn = carFunction ?: return null
        val method = getFunctionValue ?: return null
        val startedAt = SystemClock.elapsedRealtime()
        val failure = failureReporter(verbose, "$propertyId/$areaId")
        return try {
            val value = method.invoke(fn, *adaptArgs(method, intArrayOf(propertyId, areaId))) as Int
            if (verbose) WidgetLog.d("get prop=$propertyId area=$areaId -> $value (${ms(startedAt)} ms)")
            quietSuccess("$propertyId/$areaId")
            value
        } catch (e: Throwable) {
            failure("get prop=$propertyId area=$areaId failed", e)
            null
        }
    }

    override fun setInt(propertyId: Int, areaId: Int, value: Int): Boolean {
        val fn = carFunction ?: run {
            WidgetLog.e("set prop=$propertyId area=$areaId value=$value skipped: not connected")
            return false
        }
        val method = setFunctionValue ?: return false
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            val result = method.invoke(fn, *adaptArgs(method, intArrayOf(propertyId, areaId, value)))
            // The api carries the boolean form; a void firmware variant counts as accepted
            // unless it threw — the verdict is the exception, not a missing return value.
            val ok = result !is Boolean || result
            WidgetLog.d("set prop=$propertyId area=$areaId value=$value -> ${if (ok) "ok" else "rejected"} (${ms(startedAt)} ms)")
            ok
        } catch (e: Throwable) {
            val cause = (e as? InvocationTargetException)?.cause ?: e
            // The cause is the verdict (log it the same way) — SecurityException would mean a
            // firmware that DOES gate this channel, IllegalArgumentException an unknown
            // property: both are diagnostic data, not a silent failure.
            WidgetLog.e("set prop=$propertyId area=$areaId value=$value failed: $cause")
            false
        }
    }

    /**
     * One-time support probe for every property the widget may touch: `isFunctionSupported`
     * answers the firmware's own FunctionStatus enum per property/area, so the log shows
     * exactly which functions this car exposes on this channel. Verdicts are kept:
     * [isFunctionSupported] replays them (the :app panel proved they predict the write
     * verdict — a notavailable property's write came back rejected).
     */
    private val probeVerdicts = HashMap<String, String>()

    override fun isFunctionSupported(propertyId: Int, areaId: Int): Boolean =
        synchronized(probeVerdicts) { probeVerdicts["$propertyId/$areaId"] }?.let { it == "active" } ?: true

    private fun probeTrackedFunctions(fn: Any, statusMethod: Method, context: Context) {
        val model = WidgetSettings(context).carModel
        for (spec in WidgetFunctions.all(model)) {
            val verdict = try {
                statusMethod.invoke(fn, *adaptArgs(statusMethod, intArrayOf(spec.propertyId, spec.areaId)))
                    ?.let { (it as? Enum<*>)?.name ?: it.toString() }
                    ?: "null"
            } catch (e: Throwable) {
                val cause = (e as? InvocationTargetException)?.cause ?: e
                "error: $cause"
            }
            synchronized(probeVerdicts) { probeVerdicts["${spec.propertyId}/${spec.areaId}"] = verdict }
            WidgetLog.i("probe: ${spec.id} prop=${spec.propertyId} area=${spec.areaId} -> $verdict")
        }
    }

    /** A failing property would otherwise write an error line on every poll. */
    private fun failureReporter(verbose: Boolean, key: String): (String, Throwable) -> Unit =
        { message, e ->
            val cause = (e as? InvocationTargetException)?.cause ?: e
            val report = verbose || synchronized(quietFailuresReported) { quietFailuresReported.add(key) }
            if (report) WidgetLog.e("$message: $cause")
        }

    private fun quietSuccess(key: String) {
        synchronized(quietFailuresReported) { quietFailuresReported.remove(key) }
    }

    private fun fail(msg: String, tr: Throwable? = null): Boolean {
        WidgetLog.e(msg, tr)
        return false
    }

    private fun ms(since: Long): Long = SystemClock.elapsedRealtime() - since
}
