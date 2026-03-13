package com.nfriend.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nfriend.app.R
import com.nfriend.app.data.Friend
import com.nfriend.app.data.FriendRepository
import java.text.SimpleDateFormat
import java.util.*

/**
 * Displays the full friend list with remove functionality.
 */
class FriendsFragment : Fragment() {

    private lateinit var friendRepo: FriendRepository
    private lateinit var adapter: FriendListAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_friends, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        friendRepo = FriendRepository(requireContext())

        val recyclerView = view.findViewById<RecyclerView>(R.id.friends_list)
        val emptyState = view.findViewById<View>(R.id.friends_empty_state)
        val countText = view.findViewById<TextView>(R.id.friends_count)

        adapter = FriendListAdapter { friend ->
            // Show remove confirmation dialog
            AlertDialog.Builder(requireContext())
                .setMessage(getString(R.string.friends_remove_confirm, friend.alias))
                .setPositiveButton(R.string.confirm) { _, _ ->
                    friendRepo.removeFriend(friend.publicKey)
                    refreshList()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val friends = friendRepo.getAllFriends()
        val recyclerView = view?.findViewById<RecyclerView>(R.id.friends_list)
        val emptyState = view?.findViewById<View>(R.id.friends_empty_state)
        val countText = view?.findViewById<TextView>(R.id.friends_count)

        adapter.updateItems(friends)
        countText?.text = getString(R.string.friends_count, friends.size)

        if (friends.isEmpty()) {
            recyclerView?.visibility = View.GONE
            emptyState?.visibility = View.VISIBLE
        } else {
            recyclerView?.visibility = View.VISIBLE
            emptyState?.visibility = View.GONE
        }
    }
}

/**
 * RecyclerView adapter for the friend list.
 */
class FriendListAdapter(
    private val onRemoveClick: (Friend) -> Unit
) : RecyclerView.Adapter<FriendListAdapter.ViewHolder>() {

    private val items = mutableListOf<Friend>()
    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    fun updateItems(newItems: List<Friend>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_friend, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val avatar: TextView = view.findViewById(R.id.friend_avatar)
        private val name: TextView = view.findViewById(R.id.friend_name)
        private val addedDate: TextView = view.findViewById(R.id.friend_added_date)
        private val removeBtn: ImageView = view.findViewById(R.id.btn_remove_friend)

        fun bind(friend: Friend) {
            avatar.text = friend.alias.firstOrNull()?.uppercase() ?: "?"
            name.text = friend.alias
            addedDate.text = "Added ${dateFormat.format(Date(friend.addedAt))}"
            removeBtn.setOnClickListener { onRemoveClick(friend) }
        }
    }
}
