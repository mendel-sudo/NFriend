package com.nfriend.app.location

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Geohash encoder: converts lat/lng to geohash strings and computes
 * HMAC-SHA256 hashed versions for server-side PSI.
 *
 * Geohash precision 6 ≈ 1.2 km × 0.6 km cells.
 */
class GeohashEncoder {

    companion object {
        private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"
        private const val DEFAULT_PRECISION = 6
    }

    /**
     * Encode latitude/longitude into a geohash string.
     *
     * @param lat       Latitude (-90 to 90)
     * @param lng       Longitude (-180 to 180)
     * @param precision Number of characters in the geohash (default: 6)
     * @return Geohash string
     */
    fun encode(lat: Double, lng: Double, precision: Int = DEFAULT_PRECISION): String {
        var latMin = -90.0; var latMax = 90.0
        var lngMin = -180.0; var lngMax = 180.0

        var isLng = true
        var bit = 0
        var charIndex = 0
        val hash = StringBuilder()

        while (hash.length < precision) {
            if (isLng) {
                val mid = (lngMin + lngMax) / 2
                if (lng >= mid) {
                    charIndex = charIndex or (1 shl (4 - bit))
                    lngMin = mid
                } else {
                    lngMax = mid
                }
            } else {
                val mid = (latMin + latMax) / 2
                if (lat >= mid) {
                    charIndex = charIndex or (1 shl (4 - bit))
                    latMin = mid
                } else {
                    latMax = mid
                }
            }

            isLng = !isLng
            bit++

            if (bit == 5) {
                hash.append(BASE32[charIndex])
                bit = 0
                charIndex = 0
            }
        }

        return hash.toString()
    }

    /**
     * Get the 8 neighboring geohash cells + the center cell (9 total).
     * This handles border effects in proximity detection.
     *
     * TODO: Implement proper neighbor computation using geohash bit manipulation.
     *       For now, returns the center cell plus cells at slight offsets.
     */
    fun getNeighborhood(lat: Double, lng: Double, precision: Int = DEFAULT_PRECISION): List<String> {
        val center = encode(lat, lng, precision)

        // Approximate cell size at precision 6: ~1.2km × 0.6km
        // Offset by roughly half a cell in each direction
        val latOffset = 0.005  // ~0.6 km
        val lngOffset = 0.010  // ~1.2 km

        val offsets = listOf(
            Pair(0.0, 0.0),           // center
            Pair(latOffset, 0.0),     // north
            Pair(-latOffset, 0.0),    // south
            Pair(0.0, lngOffset),     // east
            Pair(0.0, -lngOffset),    // west
            Pair(latOffset, lngOffset),    // NE
            Pair(latOffset, -lngOffset),   // NW
            Pair(-latOffset, lngOffset),   // SE
            Pair(-latOffset, -lngOffset),  // SW
        )

        return offsets
            .map { (dLat, dLng) -> encode(lat + dLat, lng + dLng, precision) }
            .distinct()
    }

    /**
     * Hash a geohash string using HMAC-SHA256 with the server's rotating salt.
     *
     * @param geohash The raw geohash string
     * @param salt    The current epoch salt from the relay server
     * @return Hex-encoded HMAC-SHA256 digest
     */
    fun hmacHash(geohash: String, salt: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(salt.toByteArray(), "HmacSHA256")
        mac.init(keySpec)
        val hash = mac.doFinal(geohash.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}
