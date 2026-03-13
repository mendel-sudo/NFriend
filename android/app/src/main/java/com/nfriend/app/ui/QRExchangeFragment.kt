package com.nfriend.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.view.PreviewView
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayout
import com.nfriend.app.R
import com.nfriend.app.crypto.KeyManager
import com.nfriend.app.data.FriendRepository
import com.nfriend.app.qr.QRGenerator
import com.nfriend.app.qr.QRScanner

/**
 * QR Exchange screen with tabbed layout: Show QR / Scan QR.
 */
class QRExchangeFragment : Fragment() {

    private lateinit var keyManager: KeyManager
    private lateinit var friendRepo: FriendRepository
    private lateinit var qrGenerator: QRGenerator
    private lateinit var qrScanner: QRScanner

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_qr_exchange, container, false)

    @ExperimentalGetImage
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ctx = requireContext()
        keyManager = KeyManager(ctx)
        friendRepo = FriendRepository(ctx)
        qrGenerator = QRGenerator()
        qrScanner = QRScanner(keyManager)

        val tabs = view.findViewById<TabLayout>(R.id.qr_tabs)
        val showContainer = view.findViewById<View>(R.id.qr_show_container)
        val scanContainer = view.findViewById<View>(R.id.qr_scan_container)
        val qrImage = view.findViewById<ImageView>(R.id.qr_image)
        val aliasLabel = view.findViewById<TextView>(R.id.qr_alias_label)
        val cameraPreview = view.findViewById<PreviewView>(R.id.camera_preview)

        // Generate and display our QR code
        val pubKeyHex = keyManager.getPublicKeyHex() ?: ""
        val alias = keyManager.getAlias() ?: "Unknown"

        val bitmap = qrGenerator.generateFriendQR(pubKeyHex, alias)
        qrImage.setImageBitmap(bitmap)
        aliasLabel.text = alias

        // Tab switching
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        showContainer.visibility = View.VISIBLE
                        scanContainer.visibility = View.GONE
                    }
                    1 -> {
                        showContainer.visibility = View.GONE
                        scanContainer.visibility = View.VISIBLE
                        startScanner(cameraPreview)
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    @ExperimentalGetImage
    private fun startScanner(previewView: PreviewView) {
        qrScanner.startScanning(
            context = requireContext(),
            lifecycleOwner = viewLifecycleOwner,
            previewView = previewView,
            onFriendScanned = { friend ->
                friendRepo.addFriend(friend)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.friends_added, friend.alias),
                    Toast.LENGTH_LONG
                ).show()

                // Switch back to show tab
                view?.findViewById<TabLayout>(R.id.qr_tabs)?.getTabAt(0)?.select()
            },
            onError = { error ->
                Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            }
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        qrScanner.shutdown()
    }
}
