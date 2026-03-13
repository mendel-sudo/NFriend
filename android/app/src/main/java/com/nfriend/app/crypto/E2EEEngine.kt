package com.nfriend.app.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.SecretBox
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-End Encryption engine for relay payloads.
 *
 * Uses Lazysodium (libsodium) for:
 *   - crypto_box_seal()     → encrypt to a public key (anonymous sender)
 *   - crypto_box_seal_open() → decrypt with private key
 *
 * Supports typed payloads:
 *   - "gps" — proximity pings (lat/lng)
 *   - "msg" — chat messages
 *   - "pin" — precise location pins (opens in Google Maps)
 *
 * Each encrypted payload contains a nonce + timestamp for replay protection.
 * Recipients reject payloads older than MAX_AGE_SECONDS or with a seen nonce.
 */
class E2EEEngine(private val keyManager: KeyManager) {

    companion object {
        /** Maximum age of a decrypted payload before it's considered stale. */
        const val MAX_AGE_SECONDS = 600L // 10 minutes

        /** Maximum number of seen nonces to track (FIFO eviction). */
        const val MAX_NONCE_CACHE = 1000
    }

    private val sodium: LazySodiumAndroid get() = keyManager.sodium
    private val gson = Gson()

    /** FIFO cache of seen nonces for replay protection. */
    private val seenNonces = LinkedHashSet<String>()

    // ── Payload Data Classes ────────────────────────────────────────────

    /**
     * Generic typed payload. The `type` field determines which fields are used:
     *   - "gps": lat, lng, ts, nonce
     *   - "msg": text, ts, nonce
     *   - "pin": lat, lng, label, ts, nonce
     */
    data class RelayPayload(
        val type: String,        // "gps", "msg", or "pin"
        val lat: Double? = null,
        val lng: Double? = null,
        val text: String? = null,
        val label: String? = null,
        val ts: Long,
        val nonce: String
    )

    // ── Encrypt ─────────────────────────────────────────────────────────

    /**
     * Encrypt a GPS payload for a specific friend using crypto_box_seal.
     *
     * @param latitude  Current latitude
     * @param longitude Current longitude
     * @param recipientPublicKey Friend's X25519 public key (32 bytes)
     * @return Encrypted payload bytes
     */
    fun encryptPayload(
        latitude: Double,
        longitude: Double,
        recipientPublicKey: ByteArray
    ): ByteArray {
        val payload = RelayPayload(
            type = "gps",
            lat = latitude,
            lng = longitude,
            ts = System.currentTimeMillis() / 1000,
            nonce = UUID.randomUUID().toString()
        )
        return sealEncrypt(payload, recipientPublicKey)
    }

    /**
     * Encrypt a chat message for a specific friend.
     *
     * @param message   The message text
     * @param recipientPublicKey Friend's X25519 public key (32 bytes)
     * @return Encrypted payload bytes
     */
    fun encryptMessage(
        message: String,
        recipientPublicKey: ByteArray
    ): ByteArray {
        val payload = RelayPayload(
            type = "msg",
            text = message,
            ts = System.currentTimeMillis() / 1000,
            nonce = UUID.randomUUID().toString()
        )
        return sealEncrypt(payload, recipientPublicKey)
    }

    /**
     * Encrypt a location pin for a specific friend.
     * The recipient can tap to open the pin in Google Maps.
     *
     * @param latitude  Precise latitude
     * @param longitude Precise longitude
     * @param label     Optional label (e.g. "Meet me here")
     * @param recipientPublicKey Friend's X25519 public key (32 bytes)
     * @return Encrypted payload bytes
     */
    fun encryptLocationPin(
        latitude: Double,
        longitude: Double,
        label: String?,
        recipientPublicKey: ByteArray
    ): ByteArray {
        val payload = RelayPayload(
            type = "pin",
            lat = latitude,
            lng = longitude,
            label = label,
            ts = System.currentTimeMillis() / 1000,
            nonce = UUID.randomUUID().toString()
        )
        return sealEncrypt(payload, recipientPublicKey)
    }

    /**
     * Common crypto_box_seal encryption.
     */
    private fun sealEncrypt(payload: RelayPayload, recipientPublicKey: ByteArray): ByteArray {
        require(recipientPublicKey.size == Box.PUBLICKEYBYTES) {
            "Invalid public key size: ${recipientPublicKey.size}"
        }

        val plaintext = gson.toJson(payload).toByteArray(Charsets.UTF_8)
        val ciphertext = ByteArray(Box.SEALBYTES + plaintext.size)
        val success = sodium.cryptoBoxSeal(
            ciphertext,
            plaintext,
            plaintext.size.toLong(),
            recipientPublicKey
        )

        if (!success) throw RuntimeException("Encryption failed")
        return ciphertext
    }

    // ── Decrypt ─────────────────────────────────────────────────────────

    /**
     * Decrypt a received payload using our private key.
     *
     * Enforces replay protection:
     *   1. Rejects payloads older than MAX_AGE_SECONDS
     *   2. Rejects payloads with previously-seen nonces
     *
     * @param ciphertext The encrypted bytes from the relay/mesh envelope
     * @return Decrypted RelayPayload, or null if decryption fails or replay detected
     */
    fun decryptPayload(ciphertext: ByteArray): RelayPayload? {
        val publicKey = keyManager.getPublicKey() ?: return null
        val privateKey = keyManager.getPrivateKey() ?: return null

        if (ciphertext.size < Box.SEALBYTES) return null

        // crypto_box_seal_open: decrypt with our keypair
        val plaintext = ByteArray(ciphertext.size - Box.SEALBYTES)
        val success = sodium.cryptoBoxSealOpen(
            plaintext,
            ciphertext,
            ciphertext.size.toLong(),
            publicKey,
            privateKey
        )

        if (!success) return null // Decryption failed (wrong key or tampered)

        // Parse the JSON payload
        val payload = try {
            gson.fromJson(String(plaintext, Charsets.UTF_8), RelayPayload::class.java)
        } catch (e: Exception) {
            return null
        }

        // ── Replay protection ───────────────────────────────────────────

        // Check timestamp freshness
        val now = System.currentTimeMillis() / 1000
        if (kotlin.math.abs(now - payload.ts) > MAX_AGE_SECONDS) {
            return null // Too old or too far in the future
        }

        // Check nonce uniqueness
        synchronized(seenNonces) {
            if (payload.nonce in seenNonces) {
                return null // Replay detected
            }
            seenNonces.add(payload.nonce)
            // FIFO eviction
            if (seenNonces.size > MAX_NONCE_CACHE) {
                val iterator = seenNonces.iterator()
                iterator.next()
                iterator.remove()
            }
        }

        return payload
    }

    // ── Ephemeral Sender Tokens ─────────────────────────────────────────

    /**
     * Derive an ephemeral sender token for a specific friend + epoch.
     *
     * token = HMAC-SHA256(key = sharedSecret ‖ epochId, data = senderPublicKey)
     *
     * @param sharedSecret The ECDH shared secret with this friend (32 bytes)
     * @param epochId      Current salt epoch ID from the relay server
     * @param publicKey    The public key to tokenize (sender's or friend's)
     * @return 64-char hex string token
     */
    fun deriveEphemeralToken(
        sharedSecret: ByteArray,
        epochId: Int,
        publicKey: ByteArray
    ): String {
        val epochBytes = byteArrayOf(
            (epochId shr 24).toByte(),
            (epochId shr 16).toByte(),
            (epochId shr 8).toByte(),
            epochId.toByte()
        )
        val hmacKey = sharedSecret + epochBytes

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        val hash = mac.doFinal(publicKey)

        return hash.joinToString("") { "%02x".format(it) }
    }

    // ── Client-Side Proof-of-Work ───────────────────────────────────────

    /**
     * Solve a proof-of-work challenge.
     *
     * Find a nonce such that SHA-256(challenge + nonce) has at least
     * [difficulty] leading zero bits.
     *
     * @param challenge The challenge string (typically the hashed_geo)
     * @param difficulty Number of leading zero bits required (default: 16, ~65K hashes)
     * @return The hex nonce string
     */
    fun solveProofOfWork(challenge: String, difficulty: Int = 16): String {
        val fullBytes = difficulty / 8
        val remainingBits = difficulty % 8
        val mask = if (remainingBits > 0) (0xFF shl (8 - remainingBits)) and 0xFF else 0

        var nonce = 0L
        while (true) {
            val nonceStr = nonce.toString(16)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest((challenge + nonceStr).toByteArray())

            // Check leading zero bits
            var valid = true
            for (i in 0 until fullBytes) {
                if (digest[i].toInt() and 0xFF != 0) { valid = false; break }
            }
            if (valid && remainingBits > 0) {
                if ((digest[fullBytes].toInt() and mask) != 0) valid = false
            }

            if (valid) return nonceStr
            nonce++
        }
    }
}
