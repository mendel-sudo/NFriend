'use strict';

const crypto = require('crypto');

/**
 * Salt rotation service.
 * Manages rotating HMAC salts for geohash privacy.
 * Each salt epoch lasts SALT_EPOCH_SECONDS (default 300s / 5 min).
 */
class SaltRotation {
  /**
   * @param {import('better-sqlite3').Database} db
   * @param {number} epochSeconds - Duration of each salt epoch in seconds.
   */
  constructor(db, epochSeconds = 300) {
    this.db = db;
    this.epochSeconds = epochSeconds;
    this._interval = null;

    // Prepared statements for performance
    this._insertEpoch = db.prepare(
      'INSERT INTO salt_epochs (salt, valid_from, valid_until) VALUES (?, ?, ?)'
    );
    this._getCurrentSalts = db.prepare(
      'SELECT epoch_id, salt, valid_from, valid_until FROM salt_epochs WHERE valid_until > ? ORDER BY epoch_id DESC LIMIT 3'
    );
    this._cleanOldEpochs = db.prepare(
      'DELETE FROM salt_epochs WHERE valid_until < ?'
    );

    // Ensure at least one salt exists on startup
    this._ensureCurrentSalt();
  }

  /** Generate a new random salt epoch. */
  rotate() {
    const now = Math.floor(Date.now() / 1000);
    const salt = crypto.randomBytes(32).toString('hex');
    const validFrom = now;
    const validUntil = now + this.epochSeconds;

    const info = this._insertEpoch.run(salt, validFrom, validUntil);

    // Clean up epochs older than 2 periods
    this._cleanOldEpochs.run(now - this.epochSeconds * 2);

    return {
      epoch_id: Number(info.lastInsertRowid),
      salt,
      valid_from: validFrom,
      valid_until: validUntil,
    };
  }

  /** Get current valid salts (up to 3 for boundary tolerance). */
  getCurrentSalts() {
    const now = Math.floor(Date.now() / 1000);
    return this._getCurrentSalts.all(now);
  }

  /** Get the latest (most current) salt. */
  getLatestSalt() {
    const salts = this.getCurrentSalts();
    if (salts.length === 0) return this.rotate();
    return salts[0];
  }

  /** Start automatic rotation interval. */
  startAutoRotation() {
    this._ensureCurrentSalt();
    this._interval = setInterval(() => this.rotate(), this.epochSeconds * 1000);
    return this._interval;
  }

  /** Stop automatic rotation. */
  stopAutoRotation() {
    if (this._interval) {
      clearInterval(this._interval);
      this._interval = null;
    }
  }

  /** @private */
  _ensureCurrentSalt() {
    const salts = this.getCurrentSalts();
    if (salts.length === 0) this.rotate();
  }
}

module.exports = { SaltRotation };
