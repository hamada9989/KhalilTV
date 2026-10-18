package com.aeriotv.android.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * One channel's learned time-to-first-byte plus the wall-clock time it was
 * learned. Deliberately the SAME shape as [LearnedStartBuffer] (value + learn
 * time, JSON map in preferences, TTL-expired by the reader, bounded entry
 * count): the hold-back learner's storage pattern, reused rather than
 * reinvented.
 */
data class LearnedFirstByte(val ms: Int, val learnedAtMs: Long)

private val Context.firstByteDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "aerio_first_byte_prefs",
)

/**
 * Per-channel first-byte learner for [com.aeriotv.android.core.playback.LiveStreamFailover].
 *
 * Glitzbr 2026-09-15: over-the-air HDHomeRun channels through Dispatcharr have
 * to LOCK A TUNER before a single byte exists, and a 12 s client deadline
 * walked away from a working-but-slow source onto worse backups. A channel that
 * has historically taken a long time to produce its first byte earns a
 * proportionally longer budget on later tunes, with a ceiling so a pathological
 * sample cannot stretch the wait forever and decay (TTL) so a one-off slow start
 * is forgotten.
 *
 * Device-local, never synced: like the learned hold-back this is a property of
 * THIS device's network and provider path.
 *
 * It lives in its own preferences file rather than in [AppPreferences] only
 * because [AppPreferences]'s DataStore delegate is file-private; the encode /
 * decode / bound / TTL pattern below is copied from its learned-start-buffer
 * block verbatim in spirit.
 */
@Singleton
class LiveFirstByteLearner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store get() = context.firstByteDataStore

    /** One-shot read for the failover's in-memory cache. */
    suspend fun allOnce(): Map<String, LearnedFirstByte> = decode(store.data.first())

    /**
     * Record a SUCCESSFUL time-to-first-byte for one channel. The stored value
     * is a decayed maximum: a slower start raises it at once (that is the case
     * we must not walk away from), a faster start pulls it down gradually, so
     * one bad tune does not pin the channel and one good tune does not erase a
     * genuinely slow tuner.
     */
    suspend fun record(
        channelId: String,
        observedMs: Int,
        learnedAtMs: Long = System.currentTimeMillis(),
    ): Map<String, LearnedFirstByte> {
        val id = channelId.trim()
        if (id.isBlank() || observedMs <= 0) return allOnce()
        var result: Map<String, LearnedFirstByte> = emptyMap()
        store.edit { prefs ->
            val current = decodeValues(prefs[KEY_FIRST_BYTE_MS])
            val currentAt = decodeTimes(prefs[KEY_FIRST_BYTE_AT_MS])
            val previous = current[id]
            val expired = (learnedAtMs - (currentAt[id] ?: 0L)) > LEARNED_TTL_MS
            val next = when {
                previous == null || expired -> observedMs
                observedMs >= previous -> observedMs
                // Decay toward the faster observation instead of snapping to it.
                else -> (previous - ((previous - observedMs) * DECAY_NUMERATOR / DECAY_DENOMINATOR))
            }.coerceIn(1, LEARNED_MAX_MS)
            val grown = current + (id to next)
            val grownAt = currentAt + (id to learnedAtMs)
            val overBound = grown.size > MAX_ENTRIES
            val updated = if (overBound) mapOf(id to next) else grown
            val updatedAt = if (overBound) mapOf(id to learnedAtMs) else grownAt
            prefs[KEY_FIRST_BYTE_MS] = Json.encodeToString(updated)
            prefs[KEY_FIRST_BYTE_AT_MS] = Json.encodeToString(updatedAt)
            result = zip(updated, updatedAt)
        }
        return result
    }

    private fun decodeValues(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { Json.decodeFromString<Map<String, Int>>(raw) }
            .getOrDefault(emptyMap())
    }

    private fun decodeTimes(raw: String?): Map<String, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { Json.decodeFromString<Map<String, Long>>(raw) }
            .getOrDefault(emptyMap())
    }

    private fun zip(values: Map<String, Int>, times: Map<String, Long>): Map<String, LearnedFirstByte> =
        values.mapValues { (id, ms) -> LearnedFirstByte(ms, times[id] ?: 0L) }

    private fun decode(prefs: Preferences): Map<String, LearnedFirstByte> =
        zip(decodeValues(prefs[KEY_FIRST_BYTE_MS]), decodeTimes(prefs[KEY_FIRST_BYTE_AT_MS]))

    companion object {
        private val KEY_FIRST_BYTE_MS = stringPreferencesKey("live_first_byte_ms")
        private val KEY_FIRST_BYTE_AT_MS = stringPreferencesKey("live_first_byte_at_ms")

        /** Ceiling on a single learned sample. */
        const val LEARNED_MAX_MS = 45_000

        /** Past this a learned value is ignored and relearned, so a one-off
         *  slow start does not stretch the budget forever (24 h: a tuner's
         *  lock time is a property of the channel, not of one session). */
        const val LEARNED_TTL_MS = 24L * 60L * 60L * 1000L

        /** A faster start pulls the learned value down by half the difference. */
        private const val DECAY_NUMERATOR = 1
        private const val DECAY_DENOMINATOR = 2

        private const val MAX_ENTRIES = 400
    }
}
