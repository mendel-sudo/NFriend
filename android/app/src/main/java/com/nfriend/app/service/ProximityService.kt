package com.nfriend.app.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.nfriend.app.MainActivity
import com.nfriend.app.R
import com.nfriend.app.crypto.E2EEEngine
import com.nfriend.app.crypto.KeyManager
import com.nfriend.app.data.FriendRepository
import com.nfriend.app.location.GeohashEncoder
import com.nfriend.app.location.ProximityChecker
import com.nfriend.app.network.RelayClient
import kotlinx.coroutines.*

/**
 * Foreground service that periodically checks proximity to friends.
 *
 * Runs FusedLocationProvider → GeohashEncoder → relay drop/pickup cycle.
 * Displays a persistent notification while active.
 */
class ProximityService : Service() {

    companion object {
        const val CHANNEL_ID = "nfriend_proximity"
        const val NOTIFICATION_ID = 1
        const val CHECK_INTERVAL_MS = 60_000L // 1 minute
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var proximityChecker: ProximityChecker
    private lateinit var keyManager: KeyManager
    private lateinit var friendRepo: FriendRepository

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var checkJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        keyManager = KeyManager(this)
        friendRepo = FriendRepository(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val e2ee = E2EEEngine(keyManager)
        val geohash = GeohashEncoder()
        val relay = RelayClient()
        proximityChecker = ProximityChecker(geohash, e2ee, relay)

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Scanning for nearby friends…")
        startForeground(NOTIFICATION_ID, notification)

        startPeriodicChecks()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        checkJob?.cancel()
        serviceScope.cancel()
    }

    private fun startPeriodicChecks() {
        checkJob?.cancel()
        checkJob = serviceScope.launch {
            while (isActive) {
                try {
                    performProximityCheck()
                } catch (e: Exception) {
                    // Log but don't crash the service
                    android.util.Log.e("ProximityService", "Check failed: ${e.message}")
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    @Suppress("MissingPermission") // Permission checked before service start
    private suspend fun performProximityCheck() {
        val friends = friendRepo.getAllFriends()
        if (friends.isEmpty()) return

        val pubKey = keyManager.getPublicKey() ?: return

        // Get last known location
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location == null) return@addOnSuccessListener

            serviceScope.launch {
                val results = proximityChecker.checkProximity(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    myPublicKey = pubKey,
                    friends = friends
                )

                // Update the persistent notification
                val message = if (results.isNotEmpty()) {
                    "${results.size} friend(s) nearby!"
                } else {
                    "No friends nearby. Last scan: ${
                        java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                            .format(java.util.Date())
                    }"
                }
                updateNotification(message)

                // TODO: Broadcast results to ProximityFragment if it's visible
                // (use LocalBroadcastManager or LiveData through a shared ViewModel)
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NFriend")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
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
                "Proximity Scanning",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when NFriend is scanning for nearby friends"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
