package com.andrerinas.openheadunit.geely.hvac

import android.content.Context
import com.andrerinas.openheadunit.BuildConfig

/**
 * Chooses which [CarFunctionPort] backend the climate panel drives.
 *
 * Priority (D-007, reversing D-006's write path): the ECARX adaptapi first — the only
 * channel confirmed to accept climate writes on the real units (the android.car
 * write is rejected by the firmware's config registry itself); then the standard
 * android.car backend — the proven read/fallback channel (F007); then — debug builds only —
 * the standalone mock-car app; otherwise no backend and the panel stays hidden. Pure decision
 * logic is JVM-tested (HvacPortFactoryTest).
 */
object HvacPortFactory {

    enum class Backend { ECARX, ANDROID_CAR, MOCK, NONE }

    const val MOCK_PACKAGE = "com.geely.mockcar"

    fun select(
        ecarxAvailable: Boolean,
        carAvailable: Boolean,
        debugBuild: Boolean,
        mockInstalled: Boolean,
    ): Backend = when {
        ecarxAvailable -> Backend.ECARX
        carAvailable -> Backend.ANDROID_CAR
        debugBuild && mockInstalled -> Backend.MOCK
        else -> Backend.NONE
    }

    /** Package-visibility check for [MOCK_PACKAGE] (<queries> entry required on API 30+). */
    fun isMockInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(MOCK_PACKAGE, 0)
    }.isSuccess

    /**
     * Resolves the effective port for a projection session, logging each decision. Returns
     * null when no backend is available (the caller keeps the panel hidden). Blocking work
     * (adaptapi/android.car connect, mock bind) happens on the caller — the panel's port
     * executor.
     */
    fun resolve(context: Context): CarFunctionPort? {
        val ecarxAvailable = EcarxCarPort.connect(context)
        val carAvailable = ecarxAvailable || AndroidCarPort.connect(context)
        val mockInstalled = isMockInstalled(context)
        return when (select(ecarxAvailable, carAvailable, BuildConfig.DEBUG, mockInstalled)) {
            Backend.ECARX -> EcarxCarPort

            Backend.ANDROID_CAR -> AndroidCarPort

            Backend.MOCK -> {
                HvacLog.i("port: adaptapi and android.car unavailable — falling back to the mock car app (debug)")
                AidlMockCarPort(context).takeIf { it.connect() }
            }

            Backend.NONE -> {
                HvacLog.i(
                    "port: no backend — adaptapi=$ecarxAvailable android.car=$carAvailable " +
                        "debug=${BuildConfig.DEBUG} mockInstalled=$mockInstalled"
                )
                null
            }
        }
    }
}
