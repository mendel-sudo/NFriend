package com.nfriend.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nfriend.app.R
import com.nfriend.app.crypto.E2EEEngine
import com.nfriend.app.crypto.KeyManager
import com.nfriend.app.data.FriendRepository
import com.nfriend.app.location.GeohashEncoder
import com.nfriend.app.location.ProximityChecker
import com.nfriend.app.network.RelayClient
import kotlinx.coroutines.*

/**
 * Shows nearby friends with distance information.
 * Runs proximity checks on a periodic loop.
 */
class ProximityFragment : Fragment() {

    private lateinit var keyManager: KeyManager
    private lateinit var friendRepo: FriendRepository
    private lateinit var proximityChecker: ProximityChecker
    private lateinit var adapter: NearbyFriendAdapter

    private var checkJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_proximity, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ctx = requireContext()
        keyManager = KeyManager(ctx)
        friendRepo = FriendRepository(ctx)

        val e2ee = E2EEEngine(keyManager)
        val geohash = GeohashEncoder()
        val relay = RelayClient()
        proximityChecker = ProximityChecker(geohash, e2ee, relay)

        val recyclerView = view.findViewById<RecyclerView>(R.id.nearby_list)
        val emptyState = view.findViewById<View>(R.id.empty_state)
        val statusText = view.findViewById<TextView>(R.id.proximity_status)

        adapter = NearbyFriendAdapter()
        recyclerView.layoutManager = LinearLayoutManager(ctx)
        recyclerView.adapter = adapter

        // Update UI based on friend count
        val friends = friendRepo.getAllFriends()
        if (friends.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
            statusText.text = "Add friends to start scanning"
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
            statusText.text = getString(R.string.proximity_scanning)

            // TODO: Start proximity checking loop with FusedLocationProvider
            // For now, show a placeholder message
            statusText.text = "Location service not yet wired — ${friends.size} friends ready"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        checkJob?.cancel()
    }
}

/**
 * RecyclerView adapter for nearby friends.
 */
class NearbyFriendAdapter : RecyclerView.Adapter<NearbyFriendAdapter.ViewHolder>() {

    private val items = mutableListOf<ProximityChecker.NearbyFriend>()

    fun updateItems(newItems: List<ProximityChecker.NearbyFriend>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_nearby_friend, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
    }

    override fun getItemCount() = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val avatar: TextView = view.findViewById(R.id.friend_avatar)
        private val name: TextView = view.findViewById(R.id.friend_name)
        private val distance: TextView = view.findViewById(R.id.friend_distance)

        fun bind(item: ProximityChecker.NearbyFriend) {
            avatar.text = item.alias.firstOrNull()?.uppercase() ?: "?"
            name.text = item.alias
            distance.text = when {
                item.distanceMeters == null -> "Unknown distance"
                item.distanceMeters < 100 -> "Very close (~${item.distanceMeters.toInt()}m)"
                item.distanceMeters < 1000 -> "${item.distanceMeters.toInt()}m away"
                else -> "${"%.1f".format(item.distanceMeters / 1000)}km away"
            }
        }
    }
}
