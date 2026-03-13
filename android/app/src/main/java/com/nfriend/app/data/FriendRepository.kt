package com.nfriend.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Local friend list CRUD backed by EncryptedSharedPreferences.
 *
 * All friend data (including shared secrets) is encrypted at rest
 * using a Keystore-backed master key.
 */
class FriendRepository(context: Context) {

    private val gson = Gson()

    private val prefs: SharedPreferences by lazy {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "nfriend_friends_encrypted",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Get all stored friends.
     */
    fun getAllFriends(): List<Friend> {
        val json = prefs.getString(KEY_FRIENDS, null) ?: return emptyList()
        val type = object : TypeToken<List<Friend>>() {}.type
        return gson.fromJson(json, type)
    }

    /**
     * Add a new friend (from QR scan).
     * Deduplicates by public key — if the friend already exists, updates their entry.
     */
    fun addFriend(friend: Friend) {
        val friends = getAllFriends().toMutableList()
        friends.removeAll { it.publicKey.contentEquals(friend.publicKey) }
        friends.add(friend)
        saveFriends(friends)
    }

    /**
     * Find a friend by public key.
     */
    fun findByPublicKey(publicKey: ByteArray): Friend? {
        return getAllFriends().find { it.publicKey.contentEquals(publicKey) }
    }

    /**
     * Remove a friend by public key.
     */
    fun removeFriend(publicKey: ByteArray) {
        val friends = getAllFriends().toMutableList()
        friends.removeAll { it.publicKey.contentEquals(publicKey) }
        saveFriends(friends)
    }

    /**
     * Replace the entire friend list (used during migration import).
     */
    fun replaceAll(friends: List<Friend>) {
        saveFriends(friends)
    }

    /**
     * Get friend count.
     */
    fun count(): Int = getAllFriends().size

    /**
     * Clear all friends (used during data wipe).
     */
    fun clear() {
        prefs.edit().remove(KEY_FRIENDS).apply()
    }

    private fun saveFriends(friends: List<Friend>) {
        val json = gson.toJson(friends)
        prefs.edit().putString(KEY_FRIENDS, json).apply()
    }

    companion object {
        private const val KEY_FRIENDS = "friend_list"
    }
}
