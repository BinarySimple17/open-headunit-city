package com.andrerinas.openheadunit.geely.daynight

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.andrerinas.openheadunit.geely.hvac.HvacLog
import com.andrerinas.openheadunit.geely.hvac.HvacPortFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Session owner of the day/night watch (F003): resolves a [com.andrerinas.openheadunit.geely.hvac.CarFunctionPort]
 * once per projection session on its worker thread (the HVAC factory priority: ECARX →
 * android.car → debug mock; on a real unit that is the same singleton port the climate panel
 * uses, no second binding), polls [GeelyDayNightWatcher] on the reference's 2 s rhythm and
 * republishes every confirmed value as [lastValue] — the pull-source NightModeManager's
 * CAR_SIGNAL mode consults — plus [onFlip] on the main thread. Read-only: the watch never
 * writes to the car. [start]/[stop] ride the projection session, not the panel's attach.
 */
class GeelyDayNightWatch(context: Context) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "geely-daynight").apply { isDaemon = true }
    }

    /** Last confirmed value for the CAR_SIGNAL pull: 1 = day, 0 = night, null = no answer yet. */
    @Volatile var lastValue: Int? = null
        private set

    /** Main-thread callback after each confirmed flip (the wiring feeds it into the manager). */
    @Volatile var onFlip: (() -> Unit)? = null

    @Volatile private var polling: ScheduledFuture<*>? = null
    @Volatile private var generation = 0

    fun start() {
        if (polling != null) return
        val gen = ++generation
        worker.execute {
            val port = HvacPortFactory.resolve(app)
            // A start/stop pair that crossed on the main thread must not leave a schedule behind.
            if (gen != generation) return@execute
            if (port == null) {
                HvacLog.i("daynight: no car port — the watch stays idle this session")
                return@execute
            }
            val watcher = GeelyDayNightWatcher(port) { isNight ->
                lastValue = if (isNight) DayNight.NIGHT else DayNight.DAY
                main.post { onFlip?.invoke() }
            }
            polling = worker.scheduleWithFixedDelay(
                { runCatching { watcher.pollOnce() } },
                0, DayNight.POLL_INTERVAL_MS, TimeUnit.MILLISECONDS
            )
            HvacLog.i("daynight: watch started (backend=${port.javaClass.simpleName})")
        }
    }

    fun stop() {
        generation++
        val future = polling
        polling = null
        if (future != null) {
            future.cancel(false)
            HvacLog.i("daynight: watch stopped")
        }
    }
}
