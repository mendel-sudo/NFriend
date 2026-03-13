package com.nfriend.app.qr

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Generates QR codes for in-person friend key exchange.
 *
 * The QR payload is a JSON string: {"pub":"<hex>","alias":"<name>"}
 */
class QRGenerator {

    /**
     * Generate a QR code bitmap from the user's public key and alias.
     *
     * @param publicKey User's X25519 public key (hex encoded)
     * @param alias     User's display name
     * @param size      QR code size in pixels (default 512)
     * @return Bitmap of the QR code
     */
    fun generateFriendQR(publicKey: String, alias: String, size: Int = 512): Bitmap {
        val json = """{"pub":"$publicKey","alias":"$alias"}"""
        return generateQR(json, size)
    }

    /**
     * Generate a QR code bitmap from arbitrary content.
     */
    fun generateQR(content: String, size: Int = 512): Bitmap {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        return bitmap
    }
}
