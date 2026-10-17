package com.geely.mockcar

import android.os.Bundle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The received media card (F014): the bundle keys are the IPlaybackInfo getter names
 * (see IDimMediaMock.aidl), stored verbatim so the console can render exactly what the
 * publisher sent — plus the receipt bookkeeping the console shows.
 */
object DimMockStore {

    @Volatile var lastCard: Bundle? = null
        private set

    @Volatile var cardCount: Int = 0
        private set

    @Volatile var clearedCount: Int = 0
        private set

    @Volatile var lastCardAt: Long = 0
        private set

    @Volatile var progressMs: Long = 0
        private set

    @Volatile var progressAt: Long = 0
        private set

    fun acceptCard(bundle: Bundle) {
        synchronized(this) {
            if (bundle.getString("getTitle").isNullOrEmpty()) {
                clearedCount++
            } else {
                cardCount++
            }
            lastCard = Bundle(bundle)
            lastCardAt = System.currentTimeMillis()
        }
    }

    fun acceptProgress(positionMs: Long) {
        progressMs = positionMs
        progressAt = System.currentTimeMillis()
    }

    fun clear() {
        synchronized(this) {
            lastCard = null
            cardCount = 0
            clearedCount = 0
            progressMs = 0
        }
    }

    /** One console line per card field that actually carries a value; nulls stay invisible. */
    fun describe(bundle: Bundle?): String {
        if (bundle == null) return "(no card yet)"
        val text = StringBuilder()
        for (getter in GETTERS) {
            when (val value = bundle.get(getter) ?: continue) {
                is String -> text.append(getter).append(" = \"").append(value).append("\"\n")
                else -> text.append(getter).append(" = ").append(value).append("\n")
            }
        }
        if (text.isEmpty()) text.append("(empty card — clear)\n")
        return text.toString()
    }

    val lastCardFormatted: String
        get() = describe(lastCard)

    val receiptLine: String
        get() {
            val at = lastCardAt
            val time = if (at == 0L) "-" else SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at))
            return "cards=$cardCount clears=$clearedCount last=$time progress=$progressMs"
        }

    private val GETTERS = listOf(
        "getTitle", "getArtist", "getAlbum", "getArtwork", "getNextArtwork",
        "getPreviousArtwork", "getLyric", "getMediaPath", "getDuration",
        "getPlaybackStatus", "getSourceType", "getFavoriteState", "getLoopMode",
        "getRadioMode", "getPlayingItemPositionInQueue", "getRadioFrequency",
        "getRadioStationName", "getLyricContent", "getCurrentLyricSentence", "getUUID",
    )
}
