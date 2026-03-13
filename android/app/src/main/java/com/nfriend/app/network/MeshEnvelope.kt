package com.nfriend.app.network

/**
 * Envelope transported over the mesh (Nearby Connections).
 * Same fields as the relay envelope, plus a hop counter to prevent
 * infinite forwarding in the mesh.
 */
data class MeshEnvelope(
    val id: String,
    val hashedGeo: String,
    val senderToken: String,
    val payload: String,  // base64 encrypted blob
    val hopCount: Int = 0
) {
    companion object {
        /** Maximum number of hops an envelope can travel in the mesh. */
        const val MAX_HOPS = 3
    }

    /** Create a forwarded copy with incremented hop count. */
    fun forwarded(): MeshEnvelope? {
        if (hopCount >= MAX_HOPS) return null
        return copy(hopCount = hopCount + 1)
    }
}
