package com.nfriend.app.network

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the NFriend blind relay server.
 *
 * TODO: Make baseUrl configurable from settings/build config.
 */
class RelayClient(
    private val baseUrl: String = "http://10.0.2.2:3000" // Android emulator → host
) {
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // ── Data Classes ───────────────────────────────────────────────────

    data class SaltResponse(
        val salt: String,
        @SerializedName("epoch_id") val epochId: Int,
        @SerializedName("valid_until") val validUntil: Long
    )

    data class DropRequest(
        @SerializedName("hashed_geo") val hashedGeo: String,
        @SerializedName("sender_token") val senderToken: String,
        val payload: String, // base64
        @SerializedName("ttl_seconds") val ttlSeconds: Int = 300,
        val pow: String
    )

    data class DropResponse(val id: String)

    data class PickupRequest(
        @SerializedName("hashed_geos") val hashedGeos: List<String>,
        @SerializedName("known_tokens") val knownTokens: List<String>
    )

    data class Envelope(
        val id: String,
        @SerializedName("hashed_geo") val hashedGeo: String,
        @SerializedName("sender_token") val senderToken: String,
        val payload: String,
        @SerializedName("created_at") val createdAt: Long,
        @SerializedName("expires_at") val expiresAt: Long
    )

    data class PickupResponse(val envelopes: List<Envelope>)

    // ── API Methods ────────────────────────────────────────────────────

    /**
     * GET /api/salt — Fetch the current HMAC salt.
     */
    fun getSalt(): SaltResponse? {
        val request = Request.Builder()
            .url("$baseUrl/api/salt")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                gson.fromJson(response.body?.string(), SaltResponse::class.java)
            }
        } catch (e: Exception) {
            // TODO: Proper error handling / logging
            null
        }
    }

    /**
     * POST /api/drop — Drop an encrypted envelope.
     */
    fun drop(
        hashedGeo: String,
        senderToken: String,
        payload: ByteArray,
        powNonce: String,
        ttlSeconds: Int = 300
    ): DropResponse? {
        val body = gson.toJson(
            DropRequest(
                hashedGeo = hashedGeo,
                senderToken = senderToken,
                payload = android.util.Base64.encodeToString(payload, android.util.Base64.NO_WRAP),
                ttlSeconds = ttlSeconds,
                pow = powNonce
            )
        ).toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("$baseUrl/api/drop")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                gson.fromJson(response.body?.string(), DropResponse::class.java)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * POST /api/pickup — Fetch matching envelopes.
     */
    fun pickup(hashedGeos: List<String>, knownTokens: List<String>): List<Envelope> {
        val body = gson.toJson(
            PickupRequest(hashedGeos = hashedGeos, knownTokens = knownTokens)
        ).toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("$baseUrl/api/pickup")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val result = gson.fromJson(response.body?.string(), PickupResponse::class.java)
                result?.envelopes ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Quick health check to determine if the relay server is reachable.
     * @return true if the server responds with 200 OK
     */
    fun isServerReachable(): Boolean {
        val request = Request.Builder()
            .url("$baseUrl/health")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }
}
