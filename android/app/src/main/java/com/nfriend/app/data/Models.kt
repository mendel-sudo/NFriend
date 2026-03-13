package com.nfriend.app.data

/**
 * The user's own identity.
 * Private key is stored separately in encrypted file (Keystore-wrapped).
 */
data class Identity(
    val publicKey: ByteArray,
    val alias: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Identity) return false
        return publicKey.contentEquals(other.publicKey) && alias == other.alias
    }

    override fun hashCode(): Int {
        return 31 * publicKey.contentHashCode() + alias.hashCode()
    }
}

/**
 * A known friend (public key exchanged via QR).
 * sharedSecret is derived via ECDH(myPriv, friendPub) at QR scan time.
 */
data class Friend(
    val publicKey: ByteArray,
    val sharedSecret: ByteArray,
    val alias: String,
    val addedAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Friend) return false
        return publicKey.contentEquals(other.publicKey) && alias == other.alias
    }

    override fun hashCode(): Int {
        return 31 * publicKey.contentHashCode() + alias.hashCode()
    }
}

/**
 * Migration bundle for P2P device transfer.
 */
data class MigrationBundle(
    val version: Int = 1,
    val privateKey: ByteArray,
    val alias: String,
    val friends: List<Friend>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MigrationBundle) return false
        return version == other.version &&
                privateKey.contentEquals(other.privateKey) &&
                alias == other.alias &&
                friends == other.friends
    }

    override fun hashCode(): Int {
        return 31 * privateKey.contentHashCode() + alias.hashCode()
    }
}
