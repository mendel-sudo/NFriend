'use strict';

const express = require('express');
const { v4: uuidv4 } = require('uuid');
const { dropLimiter, pickupLimiter, saltLimiter } = require('../middleware/rateLimit');

/**
 * Create the envelope routes.
 * @param {import('better-sqlite3').Database} db
 * @param {import('../services/saltRotation').SaltRotation} saltRotation
 * @param {import('../services/proofOfWork').ProofOfWork} pow
 * @param {{ defaultTTL: number }} config
 * @returns {express.Router}
 */
function createEnvelopeRoutes(db, saltRotation, pow, config) {
  const router = express.Router();

  // Prepared statements
  const insertEnvelope = db.prepare(
    'INSERT INTO envelopes (id, hashed_geo, sender_token, payload, expires_at) VALUES (?, ?, ?, ?, ?)'
  );
  const selectEnvelopes = db.prepare(
    'SELECT id, hashed_geo, sender_token, payload, created_at, expires_at FROM envelopes WHERE hashed_geo = ? AND sender_token = ? AND expires_at > ?'
  );
  const countExpired = db.prepare(
    'SELECT COUNT(*) AS cnt FROM envelopes WHERE expires_at <= ?'
  );
  const sweepExpired = db.prepare(
    'DELETE FROM envelopes WHERE expires_at <= ?'
  );

  // ── GET /api/salt ────────────────────────────────────────────────────

  router.get('/salt', saltLimiter, (_req, res) => {
    try {
      const latest = saltRotation.getLatestSalt();
      res.json({
        salt: latest.salt,
        epoch_id: latest.epoch_id,
        valid_until: latest.valid_until,
      });
    } catch (err) {
      console.error('[/api/salt] Error:', err.message);
      res.status(500).json({ error: 'Internal server error' });
    }
  });

  // ── POST /api/drop ───────────────────────────────────────────────────

  router.post('/drop', dropLimiter, (req, res) => {
    try {
      const { hashed_geo, sender_token, payload, ttl_seconds, pow: powNonce } = req.body;

      // Validate required fields
      if (!hashed_geo || typeof hashed_geo !== 'string') {
        return res.status(400).json({ error: 'hashed_geo is required (string)' });
      }
      if (!sender_token || typeof sender_token !== 'string') {
        return res.status(400).json({ error: 'sender_token is required (string)' });
      }
      if (!payload || typeof payload !== 'string') {
        return res.status(400).json({ error: 'payload is required (base64 string)' });
      }
      if (!/^[a-f0-9]{64}$/i.test(hashed_geo)) {
        return res.status(400).json({ error: 'hashed_geo must be a 64-char hex string' });
      }

      // Verify proof-of-work against hashed_geo as the challenge
      if (!powNonce || !pow.verify(hashed_geo, powNonce)) {
        return res.status(429).json({ error: 'Invalid or missing proof-of-work' });
      }

      // Calculate expiry
      const ttl = Math.min(
        Math.max(Number(ttl_seconds) || config.defaultTTL, 60),
        600
      );
      const id = uuidv4();
      const expiresAt = Math.floor(Date.now() / 1000) + ttl;

      // Store the envelope
      const payloadBuffer = Buffer.from(payload, 'base64');
      insertEnvelope.run(id, hashed_geo, sender_token, payloadBuffer, expiresAt);

      res.status(201).json({ id });
    } catch (err) {
      console.error('[/api/drop] Error:', err.message);
      res.status(500).json({ error: 'Internal server error' });
    }
  });

  // ── POST /api/pickup ─────────────────────────────────────────────────

  router.post('/pickup', pickupLimiter, (req, res) => {
    try {
      const { hashed_geos, known_tokens } = req.body;

      if (!Array.isArray(hashed_geos) || hashed_geos.length === 0) {
        return res.status(400).json({ error: 'hashed_geos must be a non-empty array' });
      }
      if (!Array.isArray(known_tokens) || known_tokens.length === 0) {
        return res.status(400).json({ error: 'known_tokens must be a non-empty array' });
      }
      if (hashed_geos.length > 27) {
        return res.status(400).json({ error: 'hashed_geos limited to 27 entries' });
      }
      if (known_tokens.length > 50) {
        return res.status(400).json({ error: 'known_tokens limited to 50 entries' });
      }

      const now = Math.floor(Date.now() / 1000);
      const seen = new Set();
      const envelopes = [];

      for (const geo of hashed_geos) {
        for (const token of known_tokens) {
          const rows = selectEnvelopes.all(geo, token, now);
          for (const row of rows) {
            if (!seen.has(row.id)) {
              seen.add(row.id);
              envelopes.push({
                id: row.id,
                hashed_geo: row.hashed_geo,
                sender_token: row.sender_token,
                payload: Buffer.from(row.payload).toString('base64'),
                created_at: row.created_at,
                expires_at: row.expires_at,
              });
            }
          }
        }
      }

      res.json({ envelopes });
    } catch (err) {
      console.error('[/api/pickup] Error:', err.message);
      res.status(500).json({ error: 'Internal server error' });
    }
  });

  // ── DELETE /api/sweep ────────────────────────────────────────────────

  router.delete('/sweep', (_req, res) => {
    try {
      const now = Math.floor(Date.now() / 1000);
      const result = sweepExpired.run(now);
      res.json({ swept: result.changes });
    } catch (err) {
      console.error('[/api/sweep] Error:', err.message);
      res.status(500).json({ error: 'Internal server error' });
    }
  });

  return router;
}

module.exports = { createEnvelopeRoutes };
