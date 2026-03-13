package com.nfriend.app.ui

import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.LocationServices
import com.nfriend.app.R
import com.nfriend.app.crypto.E2EEEngine
import com.nfriend.app.crypto.KeyManager
import com.nfriend.app.data.Friend
import com.nfriend.app.data.FriendRepository
import com.nfriend.app.data.RangePreferences
import com.nfriend.app.location.GeohashEncoder
import com.nfriend.app.location.ProximityChecker
import com.nfriend.app.network.RelayClient
import kotlinx.coroutines.*

/**
 * E2EE chat screen with a nearby friend.
 *
 * Messages and location pins are encrypted with crypto_box_seal and
 * sent through the blind relay (or mesh). Ephemeral — no history stored.
 */
class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FRIEND_PUB_KEY_HEX = "friend_pub_key_hex"
        const val EXTRA_FRIEND_ALIAS = "friend_alias"
    }

    private lateinit var keyManager: KeyManager
    private lateinit var friendRepo: FriendRepository
    private lateinit var rangePrefs: RangePreferences
    private lateinit var proximityChecker: ProximityChecker
    private lateinit var adapter: ChatAdapter

    private var friend: Friend? = null
    private val chatScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob: Job? = null

    // In-memory message list (ephemeral — lost when activity closes)
    private val messages = mutableListOf<ChatItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        keyManager = KeyManager(this)
        friendRepo = FriendRepository(this)
        rangePrefs = RangePreferences(this)

        val e2ee = E2EEEngine(keyManager)
        val geohash = GeohashEncoder()
        val relay = RelayClient()
        proximityChecker = ProximityChecker(geohash, e2ee, relay)

        // Get the friend from intent
        val alias = intent.getStringExtra(EXTRA_FRIEND_ALIAS) ?: "Unknown"
        val pubKeyHex = intent.getStringExtra(EXTRA_FRIEND_PUB_KEY_HEX)

        if (pubKeyHex != null) {
            friend = friendRepo.getAllFriends().find {
                keyManager.sodium.toHexStr(it.publicKey) == pubKeyHex
            }
        }

        // Setup toolbar
        val toolbarTitle = findViewById<TextView>(R.id.chat_toolbar_title)
        toolbarTitle.text = alias

        val btnBack = findViewById<ImageButton>(R.id.chat_btn_back)
        btnBack.setOnClickListener { finish() }

        // Setup RecyclerView
        val recyclerView = findViewById<RecyclerView>(R.id.chat_messages)
        adapter = ChatAdapter(messages) { item ->
            // Handle pin tap — open in Google Maps
            if (item is ChatItem.PinItem) {
                openInMaps(item.lat, item.lng, item.label)
            }
        }
        recyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        recyclerView.adapter = adapter

        // Send button
        val inputField = findViewById<EditText>(R.id.chat_input)
        val sendBtn = findViewById<ImageButton>(R.id.chat_btn_send)
        sendBtn.setOnClickListener {
            val text = inputField.text.toString().trim()
            if (text.isNotEmpty() && friend != null) {
                inputField.setText("")
                addMessage(ChatItem.MessageItem(text = text, isMine = true, timestamp = System.currentTimeMillis()))
                sendChatMessage(text)
            }
        }

        // Pin button — send current GPS location
        val pinBtn = findViewById<ImageButton>(R.id.chat_btn_pin)
        pinBtn.setOnClickListener {
            sendLocationPin()
        }

        // Start polling for incoming messages
        startPolling()
    }

    override fun onDestroy() {
        super.onDestroy()
        pollJob?.cancel()
        chatScope.cancel()
    }

    private fun sendChatMessage(text: String) {
        val f = friend ?: return
        val pubKey = keyManager.getPublicKey() ?: return
        val broadcastPrec = rangePrefs.getBroadcastPrecision()

        chatScope.launch {
            try {
                getLastLocation { location ->
                    chatScope.launch {
                        proximityChecker.sendMessage(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            friend = f,
                            message = text,
                            myPublicKey = pubKey,
                            broadcastPrecision = broadcastPrec
                        )
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@ChatActivity, "Send failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @Suppress("MissingPermission")
    private fun sendLocationPin() {
        val f = friend ?: return
        val pubKey = keyManager.getPublicKey() ?: return
        val broadcastPrec = rangePrefs.getBroadcastPrecision()

        getLastLocation { location ->
            val lat = location.latitude
            val lng = location.longitude

            addMessage(ChatItem.PinItem(
                lat = lat, lng = lng,
                label = "My Location",
                isMine = true,
                timestamp = System.currentTimeMillis()
            ))

            chatScope.launch {
                try {
                    proximityChecker.sendLocationPin(
                        latitude = lat,
                        longitude = lng,
                        pinLatitude = lat,
                        pinLongitude = lng,
                        label = "My Location",
                        friend = f,
                        myPublicKey = pubKey,
                        broadcastPrecision = broadcastPrec
                    )
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this@ChatActivity, "Pin send failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    @Suppress("MissingPermission")
    private fun getLastLocation(callback: (Location) -> Unit) {
        val fusedClient = LocationServices.getFusedLocationProviderClient(this)
        fusedClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                callback(location)
            } else {
                runOnUiThread {
                    Toast.makeText(this, "Location unavailable", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openInMaps(lat: Double, lng: Double, label: String?) {
        val labelEncoded = Uri.encode(label ?: "Pin")
        val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng($labelEncoded)")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.setPackage("com.google.android.apps.maps")
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            // Fallback to any maps app
            val fallback = Intent(Intent.ACTION_VIEW, uri)
            startActivity(fallback)
        }
    }

    private fun startPolling() {
        pollJob = chatScope.launch {
            while (isActive) {
                try {
                    pollForMessages()
                } catch (_: Exception) { }
                delay(5_000) // Poll every 5 seconds
            }
        }
    }

    private suspend fun pollForMessages() {
        val f = friend ?: return
        val pubKey = keyManager.getPublicKey() ?: return
        val visPrec = rangePrefs.getVisibilityPrecision()

        getLastLocation { location ->
            chatScope.launch {
                val result = proximityChecker.checkProximity(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    myPublicKey = pubKey,
                    friends = listOf(f),
                    broadcastPrecision = rangePrefs.getBroadcastPrecision(),
                    visibilityPrecision = visPrec
                )

                for (received in result.receivedPayloads) {
                    if (!received.friendPublicKey.contentEquals(f.publicKey)) continue

                    when (received.payload.type) {
                        "msg" -> {
                            val text = received.payload.text ?: continue
                            addMessage(ChatItem.MessageItem(
                                text = text,
                                isMine = false,
                                timestamp = received.payload.ts * 1000
                            ))
                        }
                        "pin" -> {
                            val lat = received.payload.lat ?: continue
                            val lng = received.payload.lng ?: continue
                            addMessage(ChatItem.PinItem(
                                lat = lat, lng = lng,
                                label = received.payload.label ?: "Location",
                                isMine = false,
                                timestamp = received.payload.ts * 1000
                            ))
                        }
                    }
                }
            }
        }
    }

    private fun addMessage(item: ChatItem) {
        runOnUiThread {
            messages.add(item)
            adapter.notifyItemInserted(messages.size - 1)
            findViewById<RecyclerView>(R.id.chat_messages).scrollToPosition(messages.size - 1)
        }
    }
}

// ── Chat Data Models ──────────────────────────────────────────────────

sealed class ChatItem {
    abstract val isMine: Boolean
    abstract val timestamp: Long

    data class MessageItem(
        val text: String,
        override val isMine: Boolean,
        override val timestamp: Long
    ) : ChatItem()

    data class PinItem(
        val lat: Double,
        val lng: Double,
        val label: String?,
        override val isMine: Boolean,
        override val timestamp: Long
    ) : ChatItem()
}

// ── Chat Adapter ──────────────────────────────────────────────────────

class ChatAdapter(
    private val items: List<ChatItem>,
    private val onPinTap: (ChatItem.PinItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_MESSAGE = 0
        const val TYPE_PIN = 1
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is ChatItem.MessageItem -> TYPE_MESSAGE
        is ChatItem.PinItem -> TYPE_PIN
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_PIN -> PinViewHolder(inflater.inflate(R.layout.item_chat_pin, parent, false))
            else -> MessageViewHolder(inflater.inflate(R.layout.item_chat_message, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ChatItem.MessageItem -> (holder as MessageViewHolder).bind(item)
            is ChatItem.PinItem -> (holder as PinViewHolder).bind(item, onPinTap)
        }
    }

    override fun getItemCount() = items.size

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val bubble: LinearLayout = view.findViewById(R.id.chat_bubble)
        private val text: TextView = view.findViewById(R.id.chat_text)
        private val time: TextView = view.findViewById(R.id.chat_time)

        fun bind(item: ChatItem.MessageItem) {
            text.text = item.text
            time.text = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                .format(java.util.Date(item.timestamp))

            // Align sent messages right, received left
            val params = bubble.layoutParams as LinearLayout.LayoutParams
            if (item.isMine) {
                params.marginStart = 64
                params.marginEnd = 0
            } else {
                params.marginStart = 0
                params.marginEnd = 64
            }
            bubble.layoutParams = params
        }
    }

    class PinViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.pin_label)
        private val coords: TextView = view.findViewById(R.id.pin_coords)
        private val openBtn: TextView = view.findViewById(R.id.pin_open_maps)

        fun bind(item: ChatItem.PinItem, onTap: (ChatItem.PinItem) -> Unit) {
            label.text = "📍 ${item.label ?: "Location"}"
            coords.text = "%.6f, %.6f".format(item.lat, item.lng)
            openBtn.setOnClickListener { onTap(item) }
            itemView.setOnClickListener { onTap(item) }
        }
    }
}
