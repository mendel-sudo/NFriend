'use strict';

const rateLimit = require('express-rate-limit');

/**
 * Rate limiting middleware configurations.
 */

// Limit /api/drop to 30 requests per minute per IP
const dropLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 30,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'Too many drop requests, slow down.' },
});

// Limit /api/pickup to 60 requests per minute per IP
const pickupLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 60,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'Too many pickup requests, slow down.' },
});

// Limit /api/salt to 120 requests per minute per IP (lightweight endpoint)
const saltLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 120,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'Too many salt requests, slow down.' },
});

module.exports = { dropLimiter, pickupLimiter, saltLimiter };
