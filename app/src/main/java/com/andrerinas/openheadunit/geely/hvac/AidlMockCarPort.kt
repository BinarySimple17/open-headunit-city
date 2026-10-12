package com.andrerinas.openheadunit.geely.hvac

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.andrerinas.openheadunit.BuildConfig
import com.geely.mockcar.ICarFunctionMock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Debug-only [CarFunctionPort] backed by the standalone mock-car app (F006,
 * `com.geely.mockcar`) via the `ICarFunctionMock` AIDL surface. Used when the ECARX
 * reflection bridge cannot resolve its classes (any non-automotive device, e.g. an
 * emulator) so the climate panel and its logging stay testable without real hardware.
 *
 * Never constructed outside [HvacPortFactory.select] gating on [BuildConfig.DEBUG]:
 * a release build must not try to bind the mock.
 *
 * Bind follows the CarFYTReceiver pattern (explicit ComponentName, BIND_AUTO_CREATE,
 * timed retries); the [CarFunctionPort] calls are synchronous because the panel invokes
 * them from its dedicated single background executor.
 */
class AidlMockCarPort(private val context: Context) : CarFunctionPort {

    private val lock = Any()
    private var service: ICarFunctionMock? = null
    private var bindLatch: CountDownLatch? = null
    private var bound = false

    override val available: Boolean
        get() = synchronized(lock) { service != null }

    /**
     * Blocking connect for the port executor thread: up to [ATTEMPTS] tries, [RETRY_MS]
     * apart. Returns false — never throws — with the reason in [HvacLog].
     */
    fun connect(): Boolean {
        if (available) return true
        HvacLog.i("mock port: binding ${MockCar.PACKAGE}/${MockCar.SERVICE}")
        val latch = CountDownLatch(1)
        synchronized(lock) { bindLatch = latch }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val stub = ICarFunctionMock.Stub.asInterface(binder)
                synchronized(lock) {
                    service = if (stub?.asBinder()?.isBinderAlive == true) stub else null
                    bound = true
                    bindLatch = null
                }
                rebindAttempts = 0
                HvacLog.i("mock port: service connected (alive=${stub != null})")
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                synchronized(lock) { service = null }
                HvacLog.w("mock port: service disconnected")
                scheduleRebind()
            }
        }
        val intent = Intent().setClassName(MockCar.PACKAGE, MockCar.SERVICE)
        for (attempt in 1..ATTEMPTS) {
            if (detached) return false
            try {
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (e: Throwable) {
                HvacLog.e("mock port: bind attempt $attempt threw", e)
            }
            if (latch.await(RETRY_MS, TimeUnit.MILLISECONDS)) break
            HvacLog.w("mock port: no service after attempt $attempt/$ATTEMPTS")
        }
        val ok = available
        if (ok) {
            // The connection must outlive this call: keep a looper thread pinned for the
            // callback dispatch and remember the connection for unbind.
            connectedConnection = connection
            HvacLog.i("mock port: ready")
        } else {
            HvacLog.i("mock port: service unreachable — panel stays hidden")
            runCatching { context.unbindService(connection) }
        }
        return ok
    }

    fun disconnect() {
        detached = true
        rebindHandler.removeCallbacksAndMessages(null)
        connectedConnection?.let { connection ->
            runCatching { context.unbindService(connection) }
        }
        connectedConnection = null
        synchronized(lock) {
            service = null
            bindLatch?.countDown()
            bindLatch = null
        }
    }

    private var connectedConnection: ServiceConnection? = null

    @Volatile private var detached = false

    // Rebind bookkeeping: a mock app update or force-stop drops the binding, and the framework
    // does not re-establish it on its own — without this the panel would report every command
    // as failed until the projection screen is reopened (seen live).
    private val rebindHandler = Handler(Looper.getMainLooper())
    private var rebindAttempts = 0

    private fun scheduleRebind() {
        if (detached) return
        val attempt = ++rebindAttempts
        if (attempt > REBIND_ATTEMPTS) {
            HvacLog.w("mock port: rebind gave up after $attempt attempts — reopen the projection to recover")
            return
        }
        rebindHandler.postDelayed({ tryRebind() }, RETRY_MS)
    }

    private fun tryRebind() {
        if (detached) return
        val connection = connectedConnection ?: return
        try {
            context.bindService(
                Intent().setClassName(MockCar.PACKAGE, MockCar.SERVICE),
                connection, Context.BIND_AUTO_CREATE
            )
            HvacLog.i("mock port: rebind attempt $rebindAttempts sent")
        } catch (e: Throwable) {
            HvacLog.e("mock port: rebind attempt $rebindAttempts failed", e)
            scheduleRebind()
        }
    }

    override fun getInt(propertyId: Int, areaId: Int): Int? {
        val stub = synchronized(lock) { service } ?: return null
        return try {
            stub.getFunctionValue(propertyId, areaId)
        } catch (e: Throwable) {
            HvacLog.e("mock port: get prop=$propertyId area=$areaId failed", e)
            null
        }
    }

    override fun setInt(propertyId: Int, areaId: Int, value: Int): Boolean {
        val stub = synchronized(lock) { service } ?: run {
            HvacLog.e("mock port: set prop=$propertyId skipped — not connected")
            return false
        }
        return try {
            stub.setFunctionValue(propertyId, areaId, value)
        } catch (e: Throwable) {
            HvacLog.e("mock port: set prop=$propertyId area=$areaId value=$value failed", e)
            false
        }
    }

    private object MockCar {
        const val PACKAGE = "com.geely.mockcar"
        const val SERVICE = "com.geely.mockcar.MockCarService"
    }

    private companion object {
        const val ATTEMPTS = 3
        const val RETRY_MS = 1_500L
        const val REBIND_ATTEMPTS = 5
    }
}
