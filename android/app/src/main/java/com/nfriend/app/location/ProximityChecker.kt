package com.nfriend.app.location

import com.nfriend.app.crypto.E2EEEngine
import com.nfriend.app.data.Friend
import com.nfriend.app.network.RelayClient

/**
 * Orchestrates the proximity check flow with configurable range:
 * 1. Get current location → geohash → neighborhood(s) at configured precisions
 * 2. Fetch salt from server
 * 3. HMAC-hash all cells (multi-precision for drops)
 * 4. For each friend: derive ephemeral token, encrypt GPS, solve PoW, drop envelope
 * 5. Pickup envelopes matching our friends at the visibility precision
 * 6. Decrypt and return typed results (GPS pings, chat messages, location pins)
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
     * A received typed payload from a friend (chat message, pin, etc).
     */
    data class ReceivedPayload(
        val alias: String,
        val friendPublicKey: ByteArray,
        val payload: E2EEEngine.RelayPayload
    )

    /**
     * Combined results from a proximity check cycle.
     */
    data class ProximityResult(
        val nearbyFriends: List<NearbyFriend>,
        val receivedPayloads: List<ReceivedPayload>
    )

    /**
     * Run a full proximity check cycle with configurable range.
     *
     * @param latitude            Our current latitude
     * @param longitude           Our current longitude
     * @param myPublicKey         Our X25519 public key
     * @param friends             List of known friends
     * @param broadcastPrecision  Geohash precision for dropping (8=100ft, 4=city)
     * @param visibilityPrecision Geohash precision for picking up (8=100ft, 4=city)
     * @return ProximityResult containing nearby friends and any chat/pin payloads
     */
    suspend fun checkProximity(
        latitude: Double,
        longitude: Double,
        myPublicKey: ByteArray,
        friends: List<Friend>,
        broadcastPrecision: Int = 6,
        visibilityPrecision: Int = 6
    ): ProximityResult {
        if (friends.isEmpty()) return ProximityResult(emptyList(), emptyList())

        // Step 1: Get current salt from relay
        val saltResponse = relayClient.getSalt() ?: return ProximityResult(emptyList(), emptyList())
        val salt = saltResponse.salt
        val epochId = saltResponse.epochId

        // Step 2: Compute multi-precision hashes for drops
        // Drop at all levels from broadcast (finest) to visibility (coarsest)
        val dropPrecisions = (broadcastPrecision downTo visibilityPrecision).toList()
        val dropHashes = geohashEncoder.getMultiPrecisionHashes(
            latitude, longitude, dropPrecisions, salt
        )

        // Step 3: Drop envelopes for each friend into all precision levels
        for (friend in friends) {
            val senderToken = e2eeEngine.deriveEphemeralToken(
                friend.sharedSecret, epochId, myPublicKey
            )
            val payload = e2eeEngine.encryptPayload(
                latitude, longitude, friend.publicKey
            )

            for (hashedGeo in dropHashes) {
                val powNonce = e2eeEngine.solveProofOfWork(hashedGeo)
                relayClient.drop(
                    hashedGeo = hashedGeo,
                    senderToken = senderToken,
                    payload = payload,
                    powNonce = powNonce
                )
            }
        }

        // Step 4: Compute pickup hashes at visibility precision only
        val pickupCells = geohashEncoder.getNeighborhood(latitude, longitude, visibilityPrecision)
        val pickupHashes = pickupCells.map { geohashEncoder.hmacHash(it, salt) }

        // Step 5: Compute friend tokens and pickup
        val friendTokens = friends.map { friend ->
            e2eeEngine.deriveEphemeralToken(friend.sharedSecret, epochId, friend.publicKey)
        }
        val envelopes = relayClient.pickup(pickupHashes, friendTokens)

        // Step 6: Decrypt and categorize results
        val nearbyFriends = mutableListOf<NearbyFriend>()
        val receivedPayloads = mutableListOf<ReceivedPayload>()

        // Map tokens to friends for sender identification
        val tokenToFriend = friends.associateBy { friend ->
            e2eeEngine.deriveEphemeralToken(friend.sharedSecret, epochId, friend.publicKey)
        }

        for (envelope in envelopes) {
            val ciphertext = android.util.Base64.decode(
                envelope.payload, android.util.Base64.DEFAULT
            )
            val relayPayload = e2eeEngine.decryptPayload(ciphertext) ?: continue
            val friend = tokenToFriend[envelope.senderToken]

            when (relayPayload.type) {
                "gps" -> {
                    val lat = relayPayload.lat ?: continue
                    val lng = relayPayload.lng ?: continue
                    val distance = haversineDistance(latitude, longitude, lat, lng)
                    nearbyFriends.add(
                        NearbyFriend(
                            alias = friend?.alias ?: "Unknown",
                            distanceMeters = distance,
                            latitude = lat,
                            longitude = lng
                        )
                    )
                }
                "msg", "pin" -> {
                    if (friend != null) {
                        receivedPayloads.add(
                            ReceivedPayload(
                                alias = friend.alias,
                                friendPublicKey = friend.publicKey,
                                payload = relayPayload
                            )
                        )
                    }
                }
            }
        }

        return ProximityResult(nearbyFriends, receivedPayloads)
    }

    /**
     * Send a chat message to a specific friend via the relay.
     *
     * @param latitude   Our current latitude (for geohash cell selection)
     * @param longitude  Our current longitude
     * @param friend     The friend to message
     * @param message    The message text
     * @param myPublicKey Our public key
     * @param broadcastPrecision Geohash precision for the drop
     */
    suspend fun sendMessage(
        latitude: Double,
        longitude: Double,
        friend: Friend,
        message: String,
        myPublicKey: ByteArray,
        broadcastPrecision: Int = 6
    ) {
        val saltResponse = relayClient.getSalt() ?: return
        val salt = saltResponse.salt
        val epochId = saltResponse.epochId

        val senderToken = e2eeEngine.deriveEphemeralToken(
            friend.sharedSecret, epochId, myPublicKey
        )
        val payload = e2eeEngine.encryptMessage(message, friend.publicKey)

        val cells = geohashEncoder.getNeighborhood(latitude, longitude, broadcastPrecision)
        for (cell in cells) {
            val hashedGeo = geohashEncoder.hmacHash(cell, salt)
            val powNonce = e2eeEngine.solveProofOfWork(hashedGeo)
            relayClient.drop(
                hashedGeo = hashedGeo,
                senderToken = senderToken,
                payload = payload,
                powNonce = powNonce
            )
        }
    }

    /**
     * Send a location pin to a specific friend via the relay.
     */
    suspend fun sendLocationPin(
        latitude: Double,
        longitude: Double,
        pinLatitude: Double,
        pinLongitude: Double,
        label: String?,
        friend: Friend,
        myPublicKey: ByteArray,
        broadcastPrecision: Int = 6
    ) {
        val saltResponse = relayClient.getSalt() ?: return
        val salt = saltResponse.salt
        val epochId = saltResponse.epochId

        val senderToken = e2eeEngine.deriveEphemeralToken(
            friend.sharedSecret, epochId, myPublicKey
        )
        val payload = e2eeEngine.encryptLocationPin(
            pinLatitude, pinLongitude, label, friend.publicKey
        )

        val cells = geohashEncoder.getNeighborhood(latitude, longitude, broadcastPrecision)
        for (cell in cells) {
            val hashedGeo = geohashEncoder.hmacHash(cell, salt)
            val powNonce = e2eeEngine.solveProofOfWork(hashedGeo)
            relayClient.drop(
                hashedGeo = hashedGeo,
                senderToken = senderToken,
                payload = payload,
                powNonce = powNonce
            )
        }
    }

    /**
     * Haversine formula to calculate distance between two GPS points.
     * @return Distance in meters
     */
    private fun haversineDistance(
        lat1: Double, lng1: Double,
        lat2: Double, lng2: Double
    ): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }
}
