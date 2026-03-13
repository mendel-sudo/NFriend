'use strict';

const Database = require('better-sqlite3');
const path = require('path');
const fs = require('fs');

/**
 * Initialize the SQLite database and create tables if they don't exist.
 * @param {string} dbPath - Path to the SQLite database file.
 * @returns {import('better-sqlite3').Database} The initialized database instance.
 */
function initDatabase(dbPath) {
  // Ensure the data directory exists
  const dir = path.dirname(dbPath);
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }

  const db = new Database(dbPath);

  // Enable WAL mode for better concurrent read performance
  db.pragma('journal_mode = WAL');

  // Create tables
  db.exec(`
    CREATE TABLE IF NOT EXISTS envelopes (
      id            TEXT PRIMARY KEY,
      hashed_geo    TEXT NOT NULL,
      sender_token  TEXT NOT NULL,
      payload       BLOB NOT NULL,
      created_at    INTEGER NOT NULL DEFAULT (strftime('%s','now')),
      expires_at    INTEGER NOT NULL
    );

    CREATE INDEX IF NOT EXISTS idx_envelopes_geo ON envelopes (hashed_geo);
    CREATE INDEX IF NOT EXISTS idx_envelopes_exp ON envelopes (expires_at);

    CREATE TABLE IF NOT EXISTS salt_epochs (
      epoch_id      INTEGER PRIMARY KEY AUTOINCREMENT,
      salt          TEXT NOT NULL,
      valid_from    INTEGER NOT NULL,
      valid_until   INTEGER NOT NULL
    );
  `);

  return db;
}

module.exports = { initDatabase };
