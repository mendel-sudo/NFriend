'use strict';

const crypto = require('crypto');

/**
 * Proof-of-Work verification service.
 *
 * The client must find a nonce such that:
 *   SHA-256(challenge + nonce) has at least `difficulty` leading zero bits.
 *
 * With difficulty=16, the client needs ~65K hash attempts (~<1s on mobile).
 */
class ProofOfWork {
  /**
   * @param {number} difficulty - Number of leading zero bits required.
   */
  constructor(difficulty = 16) {
    this.difficulty = difficulty;
  }

  /**
   * Verify a proof-of-work submission.
   * @param {string} challenge - The challenge string (typically the envelope ID).
   * @param {string} nonce - The nonce found by the client.
   * @returns {boolean} True if the proof is valid.
   */
  verify(challenge, nonce) {
    if (!challenge || !nonce) return false;

    const hash = crypto
      .createHash('sha256')
      .update(challenge + nonce)
      .digest();

    return this._hasLeadingZeroBits(hash, this.difficulty);
  }

  /**
   * Check if a hash has at least `bits` leading zero bits.
   * @param {Buffer} hash
   * @param {number} bits
   * @returns {boolean}
   * @private
   */
  _hasLeadingZeroBits(hash, bits) {
    const fullBytes = Math.floor(bits / 8);
    const remainingBits = bits % 8;

    // Check full zero bytes
    for (let i = 0; i < fullBytes; i++) {
      if (hash[i] !== 0) return false;
    }

    // Check remaining bits in the next byte
    if (remainingBits > 0) {
      const mask = 0xff << (8 - remainingBits);
      if ((hash[fullBytes] & mask) !== 0) return false;
    }

    return true;
  }

  /**
   * Solve a proof-of-work challenge (for testing purposes only).
   * @param {string} challenge
   * @returns {string} A valid nonce.
   */
  solve(challenge) {
    let nonce = 0;
    while (true) {
      const nonceStr = nonce.toString(16);
      if (this.verify(challenge, nonceStr)) {
        return nonceStr;
      }
      nonce++;
    }
  }
}

module.exports = { ProofOfWork };
