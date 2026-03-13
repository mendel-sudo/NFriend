package com.nfriend.app.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.KeyExchange
import com.goterl.lazysodium.utils.Key
import com.goterl.lazysodium.utils.KeyPair
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages all cryptographic keys for NFriend.
 *
 * Key storage strategy:
 *   - X25519 keypair is generated via Lazysodium (libsodium).
 *   - The private key is stored in an encrypted file on internal storage.
 *   - The file is encrypted with a Keystore-backed AES-256-GCM key.
 *   - This allows the private key to be exported for P2P migration
 *     (Android Keystore hardware keys are non-exportable by design).
 *
 * Provides:
 *   - generateKeyPair() — create a new X25519 identity
 *   - getPublicKey() / getPrivateKey() — read stored keys
 *   - deriveSharedSecret(friendPub) — ECDH for ephemeral token derivation
 *   - exportPrivateKey() / importPrivateKey() — for device migration
 */
class KeyManager(private val context: Context) {

    companion object {
        private const val KEYSTORE_ALIAS = "nfriend_wrapping_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val GCM_TAG_LENGTH = 128
        private const val PRIV_KEY_FILENAME = "nfriend_identity.enc"
        private const val PUB_KEY_FILENAME = "nfriend_identity.pub"
        private const val ALIAS_FILENAME = "nfriend_alias.txt"
    }

    val sodium: LazySodiumAndroid = LazySodiumAndroid(SodiumAndroid())

    // ── Key Generation ──────────────────────────────────────────────────

    /**
     * Generate a new X25519 keypair and store it.
     * The private key is encrypted with the Keystore wrapping key.
     * The public key is stored in plaintext (it's public by definition).
     *
     * @param alias User's display name
     * @return The public key bytes
     */
    fun generateKeyPair(alias: String): ByteArray {
        val keyPair: KeyPair = sodium.cryptoBoxKeypair()
        val publicKey = keyPair.publicKey.asBytes
        val privateKey = keyPair.secretKey.asBytes

        // Store public key (plaintext — it's public)
        writeFile(PUB_KEY_FILENAME, publicKey)

        // Store private key (encrypted with Keystore AES)
        val encryptedPriv = wrapEncrypt(privateKey)
        writeFile(PRIV_KEY_FILENAME, encryptedPriv)

        // Store alias
        writeFile(ALIAS_FILENAME, alias.toByteArray(Charsets.UTF_8))

        return publicKey
    }

    /**
     * Check if an identity (keypair) already exists.
     */
    fun hasIdentity(): Boolean {
        return getFile(PUB_KEY_FILENAME).exists() && getFile(PRIV_KEY_FILENAME).exists()
    }

    // ── Key Retrieval ───────────────────────────────────────────────────

    /**
     * Get the stored public key.
     * @return Public key bytes, or null if no identity exists.
     */
    fun getPublicKey(): ByteArray? {
        val file = getFile(PUB_KEY_FILENAME)
        if (!file.exists()) return null
        return file.readBytes()
    }

    /**
     * Get the stored public key as a hex string (for QR codes).
     */
    fun getPublicKeyHex(): String? {
        return getPublicKey()?.let { sodium.toHexStr(it) }
    }

    /**
     * Get the stored private key (decrypted from the Keystore-wrapped file).
     * @return Private key bytes, or null if no identity exists.
     */
    fun getPrivateKey(): ByteArray? {
        val file = getFile(PRIV_KEY_FILENAME)
        if (!file.exists()) return null
        val encrypted = file.readBytes()
        return wrapDecrypt(encrypted)
    }

    /**
     * Get the stored user alias.
     */
    fun getAlias(): String? {
        val file = getFile(ALIAS_FILENAME)
        if (!file.exists()) return null
        return file.readBytes().toString(Charsets.UTF_8)
    }

    // ── ECDH Shared Secret ──────────────────────────────────────────────

    /**
     * Derive a shared secret with a friend via X25519 ECDH.
     * This shared secret is used for ephemeral sender token derivation.
     *
     * shared_secret = crypto_scalarmult(my_private_key, friend_public_key)
     *
     * @param friendPublicKey Friend's X25519 public key (32 bytes)
     * @return 32-byte shared secret
     * @throws IllegalStateException if no identity exists
     */
    fun deriveSharedSecret(friendPublicKey: ByteArray): ByteArray {
        val privateKey = getPrivateKey()
            ?: throw IllegalStateException("No identity — call generateKeyPair() first")

        val sharedSecret = ByteArray(Box.BEFORENMBYTES)
        val success = sodium.cryptoBoxBeforeNm(
            sharedSecret,
            friendPublicKey,
            privateKey
        )

        if (!success) throw RuntimeException("ECDH key agreement failed")
        return sharedSecret
    }

    // ── Migration Export / Import ─────────────────────────────────────

    /**
     * Export the raw private key for device migration.
     * Only call this during P2P migration over Nearby Connections.
     *
     * @return Raw private key bytes (32 bytes)
     */
    fun exportPrivateKey(): ByteArray {
        return getPrivateKey()
            ?: throw IllegalStateException("No identity to export")
    }

    /**
     * Import a private key from a migration bundle.
     * Overwrites any existing identity.
     *
     * @param privateKey Raw private key bytes (32 bytes)
     * @param alias      User alias from the migration bundle
     * @return The corresponding public key bytes
     */
    fun importPrivateKey(privateKey: ByteArray, alias: String): ByteArray {
        require(privateKey.size == Box.SECRETKEYBYTES) {
            "Invalid private key size: ${privateKey.size}, expected ${Box.SECRETKEYBYTES}"
        }

        // Derive public key from private key
        val publicKey = ByteArray(Box.PUBLICKEYBYTES)
        val success = sodium.cryptoScalarmultBase(publicKey, privateKey)
        if (!success) throw RuntimeException("Failed to derive public key")

        // Store both keys
        writeFile(PUB_KEY_FILENAME, publicKey)
        writeFile(PRIV_KEY_FILENAME, wrapEncrypt(privateKey))
        writeFile(ALIAS_FILENAME, alias.toByteArray(Charsets.UTF_8))

        return publicKey
    }

    // ── Keystore-backed AES Wrapping ─────────────────────────────────

    /**
     * Get or create the Keystore-backed AES-256-GCM key used to
     * encrypt the X25519 private key at rest on disk.
     */
    private fun getOrCreateWrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.getKey(KEYSTORE_ALIAS, null)?.let { return it as SecretKey }

        val keyGen = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
        )
        keyGen.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGen.generateKey()
    }

    /**
     * Encrypt data with the Keystore AES key.
     * Format: [IV_LEN (1 byte)] [IV] [CIPHERTEXT]
     */
    private fun wrapEncrypt(plaintext: ByteArray): ByteArray {
        val key = getOrCreateWrappingKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return byteArrayOf(iv.size.toByte()) + iv + ciphertext
    }

    /**
     * Decrypt data with the Keystore AES key.
     */
    private fun wrapDecrypt(wrapped: ByteArray): ByteArray {
        val key = getOrCreateWrappingKey()
        val ivLength = wrapped[0].toInt() and 0xFF
        val iv = wrapped.sliceArray(1..ivLength)
        val ciphertext = wrapped.sliceArray((1 + ivLength) until wrapped.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(ciphertext)
    }

    // ── Wipe ────────────────────────────────────────────────────────────

    /**
     * Securely delete all identity data from this device.
     * Called after migration, or on user request.
     */
    fun wipeIdentity() {
        getFile(PRIV_KEY_FILENAME).delete()
        getFile(PUB_KEY_FILENAME).delete()
        getFile(ALIAS_FILENAME).delete()

        // Remove the Keystore wrapping key too
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            keyStore.deleteEntry(KEYSTORE_ALIAS)
        }
    }

    // ── File helpers ────────────────────────────────────────────────────

    private fun getFile(name: String): File = File(context.filesDir, name)

    private fun writeFile(name: String, data: ByteArray) {
        getFile(name).writeBytes(data)
    }
}
