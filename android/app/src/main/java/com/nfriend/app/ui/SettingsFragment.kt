package com.nfriend.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.nfriend.app.OnboardingActivity
import com.nfriend.app.R
import com.nfriend.app.crypto.KeyManager
import com.nfriend.app.data.FriendRepository
import com.nfriend.app.migration.DeviceMigration
import com.nfriend.app.data.MigrationBundle

/**
 * Settings screen: identity info, device migration, and data wipe.
 */
class SettingsFragment : Fragment() {

    private lateinit var keyManager: KeyManager
    private lateinit var friendRepo: FriendRepository
    private var migration: DeviceMigration? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ctx = requireContext()
        keyManager = KeyManager(ctx)
        friendRepo = FriendRepository(ctx)

        // Display identity info
        val aliasText = view.findViewById<TextView>(R.id.settings_alias)
        val pubKeyText = view.findViewById<TextView>(R.id.settings_pub_key)

        aliasText.text = keyManager.getAlias() ?: "Unknown"
        pubKeyText.text = keyManager.getPublicKeyHex() ?: "No key"

        // Copy public key on tap
        pubKeyText.setOnClickListener {
            val clipboard = android.content.ClipboardManager::class.java
                .cast(ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE))
            clipboard?.setPrimaryClip(
                android.content.ClipData.newPlainText("NFriend Public Key", pubKeyText.text)
            )
            Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
        }

        // ── Migration ──────────────────────────────────────────────────

        val btnMigrateSend = view.findViewById<MaterialButton>(R.id.btn_migrate_send)
        val btnMigrateReceive = view.findViewById<MaterialButton>(R.id.btn_migrate_receive)

        btnMigrateSend.setOnClickListener { startMigrationSend() }
        btnMigrateReceive.setOnClickListener { startMigrationReceive() }

        // ── Wipe ───────────────────────────────────────────────────────

        val btnWipe = view.findViewById<MaterialButton>(R.id.btn_wipe_data)
        btnWipe.setOnClickListener {
            AlertDialog.Builder(ctx)
                .setTitle(R.string.settings_wipe)
                .setMessage(R.string.settings_wipe_confirm)
                .setPositiveButton(R.string.settings_wipe_button) { _, _ ->
                    keyManager.wipeIdentity()
                    friendRepo.clear()
                    Toast.makeText(ctx, "All data wiped.", Toast.LENGTH_SHORT).show()

                    // Return to onboarding
                    startActivity(Intent(ctx, OnboardingActivity::class.java))
                    requireActivity().finish()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun startMigrationSend() {
        val ctx = requireContext()
        migration = DeviceMigration(ctx).apply {
            onVerificationCode = { code ->
                requireActivity().runOnUiThread {
                    AlertDialog.Builder(ctx)
                        .setTitle(R.string.migration_verify_title)
                        .setMessage(getString(R.string.migration_verify_message, code))
                        .setPositiveButton(R.string.confirm) { _, _ ->
                            // On confirm, the connection was already accepted in the callback
                        }
                        .setNegativeButton(R.string.cancel) { _, _ ->
                            migration?.stopAll()
                        }
                        .setCancelable(false)
                        .show()
                }
            }

            onConnectionEstablished = {
                // Send the migration bundle
                val privateKey = keyManager.exportPrivateKey()
                val alias = keyManager.getAlias() ?: ""
                val friends = friendRepo.getAllFriends()
                val bundle = MigrationBundle(
                    privateKey = privateKey,
                    alias = alias,
                    friends = friends
                )
                // TODO: Get the endpoint ID from the connection callback
                // sendMigrationBundle(endpointId, bundle)

                requireActivity().runOnUiThread {
                    Toast.makeText(ctx, R.string.migration_sending, Toast.LENGTH_SHORT).show()
                }
            }

            onError = { error ->
                requireActivity().runOnUiThread {
                    Toast.makeText(ctx, "Migration error: $error", Toast.LENGTH_LONG).show()
                }
            }
        }

        migration?.startDiscovery(keyManager.getAlias() ?: "NFriend")
        Toast.makeText(ctx, "Looking for new phone…", Toast.LENGTH_SHORT).show()
    }

    private fun startMigrationReceive() {
        val ctx = requireContext()
        migration = DeviceMigration(ctx).apply {
            onVerificationCode = { code ->
                requireActivity().runOnUiThread {
                    AlertDialog.Builder(ctx)
                        .setTitle(R.string.migration_verify_title)
                        .setMessage(getString(R.string.migration_verify_message, code))
                        .setPositiveButton(R.string.confirm) { _, _ -> }
                        .setNegativeButton(R.string.cancel) { _, _ ->
                            migration?.stopAll()
                        }
                        .setCancelable(false)
                        .show()
                }
            }

            onMigrationReceived = { bundle ->
                // Import the identity and friend list
                keyManager.importPrivateKey(bundle.privateKey, bundle.alias)
                friendRepo.replaceAll(bundle.friends)

                requireActivity().runOnUiThread {
                    Toast.makeText(ctx, R.string.migration_success, Toast.LENGTH_LONG).show()
                    // Refresh the settings view
                    view?.findViewById<TextView>(R.id.settings_alias)?.text = bundle.alias
                    view?.findViewById<TextView>(R.id.settings_pub_key)?.text =
                        keyManager.getPublicKeyHex() ?: ""
                }
            }

            onError = { error ->
                requireActivity().runOnUiThread {
                    Toast.makeText(ctx, "Migration error: $error", Toast.LENGTH_LONG).show()
                }
            }
        }

        migration?.startAdvertising(keyManager.getAlias() ?: "NFriend New")
        Toast.makeText(ctx, "Waiting for old phone…", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        migration?.stopAll()
    }
}
