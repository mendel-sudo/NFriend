package com.nfriend.app.qr

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.gson.Gson
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.nfriend.app.crypto.KeyManager
import com.nfriend.app.data.Friend
import java.util.concurrent.Executors

/**
 * QR code scanner for reading friend key exchange QR codes.
 * Uses CameraX + ML Kit Barcode Scanning.
 *
 * On successful scan, derives the ECDH shared secret and returns
 * a fully constructed Friend object ready for storage.
 */
class QRScanner(private val keyManager: KeyManager) {

    private val gson = Gson()
    private val analyzerExecutor = Executors.newSingleThreadExecutor()

    /**
     * Data class for parsed QR content.
     */
    data class FriendQRData(
        val pub: String,  // hex-encoded public key
        val alias: String
    )

    /**
     * Parse a QR code content string into FriendQRData.
     */
    fun parseQRContent(rawContent: String): FriendQRData? {
        return try {
            val data = gson.fromJson(rawContent, FriendQRData::class.java)
            if (data.pub.isNullOrBlank() || data.alias.isNullOrBlank()) null
            else data
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Convert parsed QR data into a Friend object with ECDH shared secret.
     *
     * @param qrData Parsed QR content
     * @return Friend with derived shared secret, or null if key derivation fails
     */
    fun createFriendFromQR(qrData: FriendQRData): Friend? {
        return try {
            val friendPubKey = keyManager.sodium.sodiumHex2Bin(qrData.pub)
            val sharedSecret = keyManager.deriveSharedSecret(friendPubKey)
            Friend(
                publicKey = friendPubKey,
                sharedSecret = sharedSecret,
                alias = qrData.alias,
                addedAt = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Start camera preview and barcode scanning.
     *
     * @param context        Android context
     * @param lifecycleOwner Lifecycle owner for camera binding
     * @param previewView    CameraX PreviewView to display camera feed
     * @param onFriendScanned Callback with a fully constructed Friend (including shared secret)
     * @param onError        Callback for errors
     */
    @ExperimentalGetImage
    fun startScanning(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onFriendScanned: (Friend) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val barcodeScanner = BarcodeScanning.getClient()
            var scanning = true // prevent multiple callbacks

            imageAnalysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                if (!scanning) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                processImage(imageProxy, barcodeScanner) { barcode ->
                    val rawValue = barcode.rawValue ?: return@processImage
                    val qrData = parseQRContent(rawValue) ?: return@processImage
                    val friend = createFriendFromQR(qrData) ?: return@processImage

                    scanning = false // stop scanning after first valid result
                    ContextCompat.getMainExecutor(context).execute {
                        onFriendScanned(friend)
                    }
                }
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner, cameraSelector, preview, imageAnalysis
                )
            } catch (e: Exception) {
                onError("Camera init failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Process a camera frame for barcodes.
     */
    @ExperimentalGetImage
    private fun processImage(
        imageProxy: ImageProxy,
        scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
        onBarcode: (Barcode) -> Unit
    ) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(
            mediaImage, imageProxy.imageInfo.rotationDegrees
        )

        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    if (barcode.format == Barcode.FORMAT_QR_CODE) {
                        onBarcode(barcode)
                    }
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    fun shutdown() {
        analyzerExecutor.shutdown()
    }
}
