package com.nfriend.app.location

import com.nfriend.app.crypto.E2EEEngine
import com.nfriend.app.data.Friend
import com.nfriend.app.network.RelayClient

/**
 * Orchestrates the proximity check flow:
 * 1. Get current location → geohash → neighborhood
 * 2. Fetch salt from server
 * 3. HMAC-hash all cells
 * 4. For each friend: derive ephemeral token, encrypt GPS, solve PoW, drop envelope
 * 5. Pickup envelopes matching our friends
 * 6. Decrypt and calculate proximity
 *
 * TODO: Wire up to FusedLocationProviderClient and lifecycle.
 */
class ProximityChecker(
    private val geohashEncoder: GeohashEncoder,
    private val e2eeEngine: E2EEEngine,
    private val relayClient: RelayClient
) {

    /**
     * Result of a proximity check for one friend.
     */
    data class NearbyFriend(
        val alias: String,
        val distanceMeters: Double?,
        val latitude: Double,
        val longitude: Double
    )

    /**
     * Run a full proximity check cycle.
     *
     * @param latitude    Our current latitude
     * @param longitude   Our current longitude
     * @param myPublicKey Our X25519 public key
     * @param friends     List of known friends
     * @return List of nearby friends with decrypted proximity info
     */
    suspend fun checkProximity(
        latitude: Double,
        longitude: Double,
        myPublicKey: ByteArray,
        friends: List<Friend>
    ): List<NearbyFriend> {
        if (friends.isEmpty()) return emptyList()

        // Step 1: Compute geohash neighborhood (9 cells)
        val cells = geohashEncoder.getNeighborhood(latitude, longitude)

        // Step 2: Get current salt from relay
        val saltResponse = relayClient.getSalt() ?: return emptyList()
        val salt = saltResponse.salt
        val epochId = saltResponse.epochId

        // Step 3: HMAC-hash all cells
        val hashedCells = cells.map { geohashEncoder.hmacHash(it, salt) }

        // Step 4: Drop envelopes for each friend
        for (friend in friends) {
            val senderToken = e2eeEngine.deriveEphemeralToken(
                friend.sharedSecret, epochId, myPublicKey
            )
            val payload = e2eeEngine.encryptPayload(
                latitude, longitude, friend.publicKey
            )

            // Drop into each cell of our neighborhood
            for (hashedGeo in hashedCells) {
                // Solve PoW for this cell
                val powNonce = e2eeEngine.solveProofOfWork(hashedGeo)
                relayClient.drop(
                    hashedGeo = hashedGeo,
                    senderToken = senderToken,
                    payload = payload,
                    powNonce = powNonce
                )
            }
        }

        // Step 5: Compute friend tokens and pickup
        val friendTokens = friends.map { friend ->
            e2eeEngine.deriveEphemeralToken(friend.sharedSecret, epochId, friend.publicKey)
        }
        val envelopes = relayClient.pickup(hashedCells, friendTokens)

        // Step 6: Decrypt and build results
        val nearbyFriends = mutableListOf<NearbyFriend>()
        for (envelope in envelopes) {
            val ciphertext = android.util.Base64.decode(
                envelope.payload, android.util.Base64.DEFAULT
            )
            val gpsPayload = e2eeEngine.decryptPayload(ciphertext) ?: continue

            // Match the sender token to a friend
            val tokenToFriend = friends.associateBy { friend ->
                e2eeEngine.deriveEphemeralToken(friend.sharedSecret, epochId, friend.publicKey)
            }
            val friend = tokenToFriend[envelope.senderToken]

            // Calculate distance
            val distance = haversineDistance(
                latitude, longitude,
                gpsPayload.lat, gpsPayload.lng
            )

            nearbyFriends.add(
                NearbyFriend(
                    alias = friend?.alias ?: "Unknown",
                    distanceMeters = distance,
                    latitude = gpsPayload.lat,
                    longitude = gpsPayload.lng
                )
            )
        }

        return nearbyFriends
    }

    /**
     * Haversine formula to calculate distance between two GPS points.
     * @return Distance in meters
     */
    private fun haversineDistance(
        lat1: Double, lng1: Double,
        lat2: Double, lng2: Double
    ): Double {
        val R = 6371000.0 // Earth's radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }
}
