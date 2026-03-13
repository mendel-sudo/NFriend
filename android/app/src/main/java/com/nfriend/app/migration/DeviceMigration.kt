package com.nfriend.app.migration

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.gson.Gson
import com.nfriend.app.data.Friend
import com.nfriend.app.data.MigrationBundle

/**
 * Manages device-to-device migration via Android Nearby Connections API.
 * Uses Strategy.P2P_POINT_TO_POINT for direct phone-to-phone transfer.
 *
 * Migration Flow:
 *  1. New phone starts advertising
 *  2. Old phone discovers and connects
 *  3. Both display verification code — user confirms they match
 *  4. Old phone sends migration bundle (private key + friends + alias)
 *  5. New phone imports the bundle
 *  6. Old phone prompts to wipe data
 */
class DeviceMigration(private val context: Context) {

    companion object {
        const val SERVICE_ID = "com.nfriend.app.migrate"
        private val STRATEGY = Strategy.P2P_POINT_TO_POINT
    }

    private val gson = Gson()
    private val connectionsClient by lazy { Nearby.getConnectionsClient(context) }

    // Callbacks
    var onConnectionEstablished: (() -> Unit)? = null
    var onMigrationReceived: ((MigrationBundle) -> Unit)? = null
    var onVerificationCode: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    // ── New Phone: Advertise ───────────────────────────────────────────

    /**
     * Start advertising as the new phone (receiver).
     * The old phone will discover us and initiate the connection.
     *
     * @param deviceName A display name for this device
     */
    fun startAdvertising(deviceName: String) {
        val options = AdvertisingOptions.Builder()
            .setStrategy(STRATEGY)
            .build()

        connectionsClient.startAdvertising(
            deviceName,
            SERVICE_ID,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {
            // Advertising started
        }.addOnFailureListener { e ->
            onError?.invoke("Failed to start advertising: ${e.message}")
        }
    }

    // ── Old Phone: Discover ────────────────────────────────────────────

    /**
     * Start discovering the new phone (sender).
     *
     * @param deviceName A display name for this device
     */
    fun startDiscovery(deviceName: String) {
        val options = DiscoveryOptions.Builder()
            .setStrategy(STRATEGY)
            .build()

        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            options
        ).addOnSuccessListener {
            // Discovery started
        }.addOnFailureListener { e ->
            onError?.invoke("Failed to start discovery: ${e.message}")
        }
    }

    // ── Send Migration Bundle ──────────────────────────────────────────

    /**
     * Send the migration bundle to the connected new phone.
     *
     * @param endpointId The connected endpoint ID
     * @param bundle     The migration data (private key + friends + alias)
     */
    fun sendMigrationBundle(endpointId: String, bundle: MigrationBundle) {
        val json = gson.toJson(bundle)
        val payload = Payload.fromBytes(json.toByteArray())
        connectionsClient.sendPayload(endpointId, payload)
    }

    // ── Accept Connection (with verification code) ─────────────────────

    /**
     * Accept a connection after the user has verified the authentication code.
     */
    fun acceptConnection(endpointId: String) {
        connectionsClient.acceptConnection(endpointId, payloadCallback)
    }

    /**
     * Reject a connection (codes didn't match or user cancelled).
     */
    fun rejectConnection(endpointId: String) {
        connectionsClient.rejectConnection(endpointId)
    }

    // ── Stop ───────────────────────────────────────────────────────────

    fun stopAll() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
    }

    // ── Callbacks ──────────────────────────────────────────────────────

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Display verification code for user to confirm
            val code = info.authenticationDigits
            onVerificationCode?.invoke(code)

            // TODO: Wait for user confirmation before accepting
            // For now, auto-accept (INSECURE — scaffold only)
            // In production: show UI dialog with the code
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                onConnectionEstablished?.invoke()
            } else {
                onError?.invoke("Connection failed: ${result.status.statusMessage}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            // Handle disconnection
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // Auto-request connection to discovered endpoint
            connectionsClient.requestConnection(
                "NFriend Migration",
                endpointId,
                connectionLifecycleCallback
            )
        }

        override fun onEndpointLost(endpointId: String) {
            // Handle lost endpoint
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            try {
                val bundle = gson.fromJson(String(bytes), MigrationBundle::class.java)
                onMigrationReceived?.invoke(bundle)
            } catch (e: Exception) {
                onError?.invoke("Failed to parse migration bundle: ${e.message}")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // TODO: Show transfer progress
        }
    }
}
