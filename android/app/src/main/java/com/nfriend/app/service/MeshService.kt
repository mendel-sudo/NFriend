package com.nfriend.app.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nfriend.app.MainActivity
import com.nfriend.app.R
import com.nfriend.app.network.ConnectivityObserver
import com.nfriend.app.network.MeshEnvelope
import com.nfriend.app.network.RelayClient
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service for offline mesh networking via Nearby Connections.
 *
 * Uses P2P_CLUSTER (many-to-many) strategy:
 *   - Simultaneously advertises AND discovers nearby NFriend devices
 *   - Exchanges encrypted envelopes directly over Bluetooth/WiFi Direct
 *   - Bridge mode: if this device has cellular, relays mesh envelopes to/from
 *     the server for offline peers
 *
 * Privacy: envelopes are opaque encrypted blobs — mesh peers and bridges
 * can't read any payload content (crypto_box_seal).
 */
class MeshService : Service() {

    companion object {
        const val SERVICE_ID = "com.nfriend.app.mesh"
        const val CHANNEL_ID = "nfriend_mesh"
        const val NOTIFICATION_ID = 2
        private val STRATEGY = Strategy.P2P_CLUSTER
    }

    private val gson = Gson()
    private val connectionsClient by lazy { Nearby.getConnectionsClient(this) }
    private lateinit var connectivity: ConnectivityObserver
    private val relayClient = RelayClient()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Connected peer endpoint IDs. */
    private val connectedPeers = ConcurrentHashMap<String, String>() // endpointId -> name

    /** Deduplicate envelopes by ID to avoid processing duplicates. */
    private val seenEnvelopeIds = ConcurrentHashMap.newKeySet<String>()

    /** Pending outbound envelopes waiting to be sent to newly connected peers. */
    private val outboundBuffer = ConcurrentHashMap.newKeySet<MeshEnvelope>()

    /** Callback for when envelopes are received from the mesh. */
    var onEnvelopesReceived: ((List<MeshEnvelope>) -> Unit)? = null

    // ── Lifecycle ──────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        connectivity = ConnectivityObserver(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Mesh mode active — looking for nearby devices…")
        startForeground(NOTIFICATION_ID, notification)

        startMesh()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopMesh()
        connectivity.stop()
        serviceScope.cancel()
    }

    // ── Mesh Control ────────────────────────────────────────────────────

    /** Start both advertising and discovering simultaneously. */
    private fun startMesh() {
        val advOptions = AdvertisingOptions.Builder()
            .setStrategy(STRATEGY)
            .build()

        connectionsClient.startAdvertising(
            "NFriend",
            SERVICE_ID,
            connectionLifecycleCallback,
            advOptions
        ).addOnFailureListener { e ->
            android.util.Log.e("MeshService", "Advertising failed: ${e.message}")
        }

        val discOptions = DiscoveryOptions.Builder()
            .setStrategy(STRATEGY)
            .build()

        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            discOptions
        ).addOnFailureListener { e ->
            android.util.Log.e("MeshService", "Discovery failed: ${e.message}")
        }

        updateNotification("Mesh active — ${connectedPeers.size} peers connected")
    }

    private fun stopMesh() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        connectedPeers.clear()
    }

    // ── Send Envelopes ──────────────────────────────────────────────────

    /**
     * Broadcast envelopes to all connected mesh peers.
     * Also buffers them for newly connecting peers.
     */
    fun broadcastEnvelopes(envelopes: List<MeshEnvelope>) {
        for (envelope in envelopes) {
            if (seenEnvelopeIds.contains(envelope.id)) continue
            seenEnvelopeIds.add(envelope.id)
            outboundBuffer.add(envelope)

            val json = gson.toJson(envelope)
            val payload = Payload.fromBytes(json.toByteArray())

            for (endpointId in connectedPeers.keys) {
                connectionsClient.sendPayload(endpointId, payload)
            }
        }

        // If we have internet, also bridge to the relay
        if (connectivity.hasCellular) {
            bridgeToRelay(envelopes)
        }
    }

    /**
     * Bridge mesh envelopes to the relay server (when this device has cellular).
     */
    private fun bridgeToRelay(envelopes: List<MeshEnvelope>) {
        serviceScope.launch {
            for (envelope in envelopes) {
                try {
                    val payloadBytes = android.util.Base64.decode(
                        envelope.payload, android.util.Base64.DEFAULT
                    )
                    // Solve PoW for the bridged envelope
                    val pow = com.nfriend.app.crypto.E2EEEngine(
                        com.nfriend.app.crypto.KeyManager(this@MeshService)
                    ).solveProofOfWork(envelope.hashedGeo)

                    relayClient.drop(
                        hashedGeo = envelope.hashedGeo,
                        senderToken = envelope.senderToken,
                        payload = payloadBytes,
                        powNonce = pow
                    )
                } catch (e: Exception) {
                    android.util.Log.e("MeshService", "Bridge drop failed: ${e.message}")
                }
            }
        }
    }

    // ── Nearby Connections Callbacks ────────────────────────────────────

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Auto-accept mesh connections (envelopes are encrypted anyway)
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedPeers[endpointId] = "peer"
                updateNotification("Mesh active — ${connectedPeers.size} peers connected")

                // Send buffered envelopes to the new peer
                for (envelope in outboundBuffer) {
                    val json = gson.toJson(envelope)
                    connectionsClient.sendPayload(
                        endpointId, Payload.fromBytes(json.toByteArray())
                    )
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedPeers.remove(endpointId)
            updateNotification("Mesh active — ${connectedPeers.size} peers connected")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId == SERVICE_ID) {
                connectionsClient.requestConnection(
                    "NFriend",
                    endpointId,
                    connectionLifecycleCallback
                )
            }
        }

        override fun onEndpointLost(endpointId: String) {
            connectedPeers.remove(endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            try {
                val envelope = gson.fromJson(String(bytes), MeshEnvelope::class.java)

                // Deduplicate
                if (seenEnvelopeIds.contains(envelope.id)) return
                seenEnvelopeIds.add(envelope.id)

                // Forward to other peers (if hop count allows)
                val forwarded = envelope.forwarded()
                if (forwarded != null) {
                    val forwardJson = gson.toJson(forwarded)
                    val forwardPayload = Payload.fromBytes(forwardJson.toByteArray())
                    for (peerId in connectedPeers.keys) {
                        if (peerId != endpointId) { // Don't echo back
                            connectionsClient.sendPayload(peerId, forwardPayload)
                        }
                    }
                }

                // Bridge to relay if we have internet
                if (connectivity.hasCellular) {
                    bridgeToRelay(listOf(envelope))
                }

                // Notify listeners
                onEnvelopesReceived?.invoke(listOf(envelope))
            } catch (e: Exception) {
                android.util.Log.e("MeshService", "Parse failed: ${e.message}")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // No-op for byte payloads
        }
    }

    // ── Notifications ──────────────────────────────────────────────────

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NFriend Mesh")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mesh Networking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when NFriend is using mesh mode (offline)"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
