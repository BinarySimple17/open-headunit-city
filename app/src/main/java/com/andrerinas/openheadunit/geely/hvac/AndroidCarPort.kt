package com.andrerinas.openheadunit.geely.hvac

import android.content.Context
import android.os.SystemClock
import com.andrerinas.openheadunit.utils.AppLog
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * The production car backend (D-006): the standard automotive API, reached reflectively.
 *
 * `android.car` is not part of the regular SDK classpath, so every class, method
 * and value travels through reflection. The property IDs, zones and level codes are the same
 * VHAL bus the ECARX adaptapi exposes: only the entrance differs.
 *
 * CarPropertyManager shipped two generations of signatures, and this port adapts to whichever
 * the firmware carries: the int forms `getIntProperty(int,int)` / `setIntProperty(int,int,int)`
 * and the Class forms `getProperty(Class,int,int)` → CarPropertyValue / `setProperty(Class,int,
 * int,Object)`. Both are resolved at connect, the int forms preferred.
 *
 * NOT thread-safe against concurrent [connect]s; call once from the panel's port executor
 * before any traffic. get/set are safe from any thread once connected.
 */
object AndroidCarPort : CarFunctionPort {

    private const val CAR_CLASS = "android.car.Car"

    /** android.car.Car.PROPERTY_SERVICE */
    private const val PROPERTY_SERVICE = "property"

    /** CarPropertyValue.STATUS_AVAILABLE */
    private const val STATUS_AVAILABLE = 0

    @Volatile private var manager: Any? = null
    @Volatile private var getIntSimple: Method? = null
    @Volatile private var setIntSimple: Method? = null
    @Volatile private var getPropertyObj: Method? = null
    @Volatile private var setPropertyObj: Method? = null
    @Volatile private var carValueGet: Method? = null
    @Volatile private var carValueStatus: Method? = null

    override val available: Boolean get() = manager != null

    /**
     * Blocking connect (Car.createCar waits for the car service); must run off the main
     * thread. Returns false — never throws — with the reason in [HvacLog].
     */
    fun connect(context: Context): Boolean {
        if (available) return true
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            val carClass = Class.forName(CAR_CLASS)
            val car = carClass
                .getMethod("createCar", Context::class.java)
                .invoke(null, context.applicationContext)
                ?: return fail("android.car: Car.createCar(context) returned null after ${ms(startedAt)} ms")
            HvacLog.d("android.car: Car created in ${ms(startedAt)} ms")

            val mgr = car.javaClass
                .getMethod("getCarManager", String::class.java)
                .invoke(car, PROPERTY_SERVICE)
                ?: return fail("android.car: getCarManager(\"$PROPERTY_SERVICE\") returned null after ${ms(startedAt)} ms")

            resolveMethods(mgr)
            manager = mgr
            HvacLog.i("android.car: CarPropertyManager ready in ${ms(startedAt)} ms " +
                "(int-forms=${getIntSimple != null && setIntSimple != null})")
            dumpSupportedProperties(mgr)
            true
        } catch (_: ClassNotFoundException) {
            fail("android.car: $CAR_CLASS not on the classpath — not an automotive build")
        } catch (e: InvocationTargetException) {
            fail("android.car: init threw", e.cause ?: e)
        } catch (e: Throwable) {
            fail("android.car: init failed", e)
        }
    }

    /**
     * Picks whichever signature generation this firmware carries. CarPropertyValue accessors
     * are resolved from the Class-form return type when that form exists.
     */
    private fun resolveMethods(mgr: Any) {
        val cls = mgr.javaClass
        getIntSimple = methodOrNull(cls, "getIntProperty", Integer.TYPE, Integer.TYPE)
        setIntSimple = methodOrNull(cls, "setIntProperty", Integer.TYPE, Integer.TYPE, Integer.TYPE)
        getPropertyObj = methodOrNull(
            cls, "getProperty", Class::class.java, Integer.TYPE, Integer.TYPE
        )
        setPropertyObj = methodOrNull(
            cls, "setProperty", Class::class.java, Integer.TYPE, Integer.TYPE, Any::class.java
        )
        if (getIntSimple == null || setIntSimple == null) {
            if (getPropertyObj == null || setPropertyObj == null) {
                throw IllegalStateException(
                    "CarPropertyManager carries neither the int forms nor the Class forms " +
                        "(getInt=${getIntSimple != null}, setInt=${setIntSimple != null}, " +
                        "getObj=${getPropertyObj != null}, setObj=${setPropertyObj != null})"
                )
            }
            // Cache the CarPropertyValue accessors off the Class-form return type so the read
            // path never does runtime method lookup.
            val valueType = getPropertyObj!!.returnType
            carValueGet = valueType.getMethod("getValue")
            carValueStatus = valueType.getMethod("getStatus")
        }
    }

    private fun methodOrNull(cls: Class<*>, name: String, vararg params: Class<*>): Method? =
        try {
            cls.getMethod(name, *params)
        } catch (_: NoSuchMethodException) {
            null
        }

    override fun getInt(propertyId: Int, areaId: Int): Int? = read(propertyId, areaId, verbose = true)

    /** Polling path: no per-call success line, or a read every couple of seconds would be the whole log. */
    override fun getIntQuiet(propertyId: Int, areaId: Int): Int? = read(propertyId, areaId, verbose = false)

    /** Quiet-path failures already reported per property; a repeat stays silent until a success. */
    private val quietFailuresReported = mutableSetOf<String>()

    /**
     * android.car's global area id is 0. [Hvac.GLOBAL_AREA] (Integer.MIN_VALUE) is the
     * adaptapi sentinel the rest of the panel speaks; it is translated here, at
     * the android.car seam — the mock port keeps its own sentinel.
     */
    private fun platformArea(areaId: Int): Int = if (areaId == Hvac.GLOBAL_AREA) 0 else areaId

    private fun read(propertyId: Int, areaId: Int, verbose: Boolean): Int? {
        val mgr = manager ?: return null
        val area = platformArea(areaId)
        val startedAt = SystemClock.elapsedRealtime()
        val failure = failureReporter(verbose, "$propertyId/$area")
        getIntSimple?.let { method ->
            return try {
                val value = method.invoke(mgr, propertyId, area) as Int
                if (verbose) HvacLog.d("get prop=$propertyId area=$area -> $value (${ms(startedAt)} ms)")
                quietSuccess("$propertyId/$area")
                value
            } catch (e: Throwable) {
                failure("get prop=$propertyId area=$area failed", e)
                null
            }
        }
        val method = getPropertyObj ?: return null
        val valueOf = carValueGet ?: return null
        val statusOf = carValueStatus ?: return null
        return try {
            val boxed = method.invoke(mgr, Integer::class.java, propertyId, area) ?: return null
            val status = statusOf.invoke(boxed) as Int
            if (status != STATUS_AVAILABLE) {
                if (verbose) HvacLog.w("get prop=$propertyId area=$area unavailable (status=$status)")
                return null
            }
            val value = valueOf.invoke(boxed) as Int
            if (verbose) HvacLog.d("get prop=$propertyId area=$area -> $value (${ms(startedAt)} ms)")
            quietSuccess("$propertyId/$area")
            value
        } catch (e: Throwable) {
            failure("get prop=$propertyId area=$area failed", e)
            null
        }
    }

    /**
     * A failing property would otherwise write an error line on every poll (a denied
     * CAR_VENDOR_EXTENSION, a property the firmware lacks): the first failure is reported,
     * repeats stay silent until the property answers again.
     */
    private fun failureReporter(verbose: Boolean, key: String): (String, Throwable) -> Unit =
        { message, e ->
            val cause = (e as? InvocationTargetException)?.cause ?: e
            val report = verbose || synchronized(quietFailuresReported) { quietFailuresReported.add(key) }
            if (report) HvacLog.e("$message: $cause")
        }

    private fun quietSuccess(key: String) {
        synchronized(quietFailuresReported) { quietFailuresReported.remove(key) }
    }

    override fun setInt(propertyId: Int, areaId: Int, value: Int): Boolean {
        val mgr = manager ?: run {
            HvacLog.e("set prop=$propertyId area=$areaId value=$value skipped: not connected")
            return false
        }
        val area = platformArea(areaId)
        val startedAt = SystemClock.elapsedRealtime()
        setIntSimple?.let { method ->
            return try {
                method.invoke(mgr, propertyId, area, value)
                HvacLog.d("set prop=$propertyId area=$area value=$value -> ok (${ms(startedAt)} ms)")
                true
            } catch (e: Throwable) {
                logCallFailure("set", propertyId, area, e)
                false
            }
        }
        val method = setPropertyObj ?: return false
        return try {
            method.invoke(mgr, Integer::class.java, propertyId, area, value)
            HvacLog.d("set prop=$propertyId area=$area value=$value -> ok (${ms(startedAt)} ms)")
            true
        } catch (e: Throwable) {
            logCallFailure("set", propertyId, area, e)
            false
        }
    }

    /**
     * One-time catalog of what this firmware's VHAL actually registers, straight into the HVAC
     * log. The reference property IDs are model-gated, and the
     * first on-HU round proved it the hard way: recirculation exists in the car, but its
     * reference ID is not registered on this unit ("Property Id 0x10030100 does not exist in
     * the vehicle"). The catalog is the ground truth for re-mapping IDs per model — and later
     * for graying out functions a given car really lacks. Every call is reflective for the
     * same reason the rest of the port is; any failure only costs the dump, not the port.
     */
    private fun dumpSupportedProperties(mgr: Any) {
        try {
            val configs = mgr.javaClass.getMethod("getPropertyList").invoke(mgr) as? List<*>
            if (configs == null) {
                HvacLog.w("android.car: getPropertyList() unavailable — no catalog")
                return
            }
            HvacLog.i("android.car: VHAL registers ${configs.size} properties")
            val ids = configs.mapNotNull { config ->
                config?.let { runCatching { it.javaClass.getMethod("getPropertyId").invoke(it) as Int }.getOrNull() }
            }.distinct().sorted()
            ids.chunked(8).forEach { chunk ->
                HvacLog.d("catalog: " + chunk.joinToString(" ") { "0x%08X".format(it) })
            }
            val tracked = trackedPropertyIds()
            for (config in configs) {
                config ?: continue
                val id = runCatching { config.javaClass.getMethod("getPropertyId").invoke(config) as Int }
                    .getOrNull() ?: continue
                if (id !in tracked) continue
                val areas = runCatching { config.javaClass.getMethod("getAreaIds").invoke(config) as IntArray }
                    .getOrDefault(IntArray(0))
                HvacLog.i("android.car: config 0x%08X areas=%s".format(id, areas.joinToString(",", "[", "]")))
            }
        } catch (e: Throwable) {
            HvacLog.e("android.car: property catalog dump failed", e)
        }
    }

    /** Every property ID the panel may touch — from the GeelyCarFunctions registry (F010). */
    private fun trackedPropertyIds(): Set<Int> = GeelyCarFunctions.trackedPropertyIds()

    private fun logCallFailure(verb: String, propertyId: Int, areaId: Int, e: Throwable) {
        // InvocationTargetException carries the binder/service verdict (SecurityException when
        // CAR_VENDOR_EXTENSION is not granted, IllegalArgumentException for an unknown
        // property on this firmware) — the cause is the diagnosis, not the wrapper.
        val cause = (e as? InvocationTargetException)?.cause ?: e
        HvacLog.e("$verb prop=$propertyId area=$areaId failed: $cause")
    }

    private fun fail(msg: String, tr: Throwable? = null): Boolean {
        HvacLog.e(msg, tr)
        AppLog.w("[HVAC] %s", msg)
        return false
    }

    private fun ms(since: Long): Long = SystemClock.elapsedRealtime() - since
}
