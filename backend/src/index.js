'use strict';

const express = require('express');
const helmet = require('helmet');
const cors = require('cors');
const path = require('path');

const { initDatabase } = require('./db/schema');
const { SaltRotation } = require('./services/saltRotation');
const { ProofOfWork } = require('./services/proofOfWork');
const { createEnvelopeRoutes } = require('./routes/envelopes');

// ── Configuration ──────────────────────────────────────────────────────

try { require('dotenv').config(); } catch (_) { /* no-op */ }

const PORT = Number(process.env.PORT) || 3000;
const DB_PATH = process.env.DB_PATH || path.join(__dirname, '..', 'data', 'nfriend.db');
const DEFAULT_TTL = Number(process.env.DEFAULT_TTL_SECONDS) || 300;
const SALT_EPOCH = Number(process.env.SALT_EPOCH_SECONDS) || 300;
const POW_DIFFICULTY = Number(process.env.POW_DIFFICULTY) || 16;

// ── Bootstrap ──────────────────────────────────────────────────────────

const db = initDatabase(DB_PATH);
const saltRotation = new SaltRotation(db, SALT_EPOCH);
const pow = new ProofOfWork(POW_DIFFICULTY);

// Start automatic salt rotation
saltRotation.startAutoRotation();

// ── Express App ────────────────────────────────────────────────────────

const app = express();

// Security middleware
app.use(helmet());
app.use(cors());

// Parse JSON bodies (capped at 4 KB)
app.use(express.json({ limit: '4kb' }));

// Health check
app.get('/health', (_req, res) => {
  res.json({ status: 'ok', timestamp: Date.now() });
});

// Mount API routes
app.use('/api', createEnvelopeRoutes(db, saltRotation, pow, {
  defaultTTL: DEFAULT_TTL,
}));

// Auto-sweep expired envelopes every 5 minutes
const sweepInterval = setInterval(() => {
  try {
    const now = Math.floor(Date.now() / 1000);
    const stmt = db.prepare('DELETE FROM envelopes WHERE expires_at <= ?');
    const result = stmt.run(now);
    if (result.changes > 0) {
      console.log(`[sweep] Cleaned up ${result.changes} expired envelope(s)`);
    }
  } catch (err) {
    console.error('[sweep] Error:', err.message);
  }
}, 5 * 60 * 1000);

// ── Start Server ───────────────────────────────────────────────────────

if (require.main === module) {
  const server = app.listen(PORT, () => {
    console.log(`NFriend blind relay listening on port ${PORT}`);
    console.log(`  Database: ${DB_PATH}`);
    console.log(`  Salt epoch: ${SALT_EPOCH}s`);
    console.log(`  PoW difficulty: ${POW_DIFFICULTY} bits`);
    console.log(`  Default TTL: ${DEFAULT_TTL}s`);
  });

  // Graceful shutdown
  const shutdown = () => {
    console.log('\nShutting down...');
    clearInterval(sweepInterval);
    saltRotation.stopAutoRotation();
    server.close(() => {
      db.close();
      process.exit(0);
    });
  };

  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

module.exports = { app, db, saltRotation, pow };
