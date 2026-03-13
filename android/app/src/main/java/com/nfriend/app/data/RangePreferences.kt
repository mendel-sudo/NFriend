package com.nfriend.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores broadcast and visibility range preferences.
 *
 * Range is expressed as geohash precision (4–8):
 *   8 → ~38m  (100 feet)
 *   7 → ~153m (500 feet)
 *   6 → ~1.2km (½ mile) — default
 *   5 → ~4.9km (3 miles)
 *   4 → ~39km  (City)
 */
class RangePreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "nfriend_range"
        private const val KEY_BROADCAST = "broadcast_precision"
        private const val KEY_VISIBILITY = "visibility_precision"
        const val DEFAULT_PRECISION = 6

        /** Ordered from closest to farthest. */
        val PRECISION_LEVELS = intArrayOf(8, 7, 6, 5, 4)

        /** Human-readable labels for each precision level. */
        val PRECISION_LABELS = mapOf(
            8 to "100 feet",
            7 to "500 feet",
            6 to "½ mile",
            5 to "3 miles",
            4 to "City"
        )

        /** Maps a slider position (0–4) to a geohash precision. */
        fun sliderToPrec(position: Int): Int =
            PRECISION_LEVELS.getOrElse(position) { DEFAULT_PRECISION }

        /** Maps a geohash precision to a slider position (0–4). */
        fun precToSlider(precision: Int): Int =
            PRECISION_LEVELS.indexOf(precision).takeIf { it >= 0 } ?: 2

        fun labelFor(precision: Int): String =
            PRECISION_LABELS[precision] ?: "Unknown"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Broadcast (how precisely you share) ──────────────────────────

    fun getBroadcastPrecision(): Int =
        prefs.getInt(KEY_BROADCAST, DEFAULT_PRECISION)

    fun setBroadcastPrecision(precision: Int) {
        prefs.edit().putInt(KEY_BROADCAST, precision).apply()
        // Enforce: visibility can't be finer than broadcast
        if (getVisibilityPrecision() > precision) {
            setVisibilityPrecision(precision)
        }
    }

    // ── Visibility (how far you scan) ────────────────────────────────

    fun getVisibilityPrecision(): Int =
        prefs.getInt(KEY_VISIBILITY, DEFAULT_PRECISION)

    fun setVisibilityPrecision(precision: Int) {
        prefs.edit().putInt(KEY_VISIBILITY, precision).apply()
        // Enforce: broadcast can't be coarser than visibility
        if (getBroadcastPrecision() < precision) {
            setBroadcastPrecision(precision)
        }
    }

    /**
     * Returns all precision levels from broadcast (finest) down to
     * visibility (coarsest) for multi-precision drops.
     * e.g. broadcast=7, visibility=5 → [7, 6, 5]
     */
    fun getDropPrecisions(): List<Int> {
        val broadcast = getBroadcastPrecision()
        val visibility = getVisibilityPrecision()
        // broadcast is always >= visibility (higher number = finer)
        return (broadcast downTo visibility).toList()
    }
}
