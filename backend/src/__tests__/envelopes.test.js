'use strict';

const request = require('supertest');
const { app, db, saltRotation, pow } = require('../index');

// Clean state before each test
beforeEach(() => {
  db.exec('DELETE FROM envelopes');
});

afterAll(() => {
  saltRotation.stopAutoRotation();
  db.close();
});

describe('GET /health', () => {
  it('returns ok status', async () => {
    const res = await request(app).get('/health');
    expect(res.status).toBe(200);
    expect(res.body.status).toBe('ok');
  });
});

describe('GET /api/salt', () => {
  it('returns a salt with epoch info', async () => {
    const res = await request(app).get('/api/salt');
    expect(res.status).toBe(200);
    expect(res.body.salt).toMatch(/^[a-f0-9]{64}$/);
    expect(res.body.epoch_id).toBeDefined();
    expect(res.body.valid_until).toBeDefined();
  });
});

describe('POST /api/drop', () => {
  const makeValidDrop = () => {
    const hashed_geo = 'a'.repeat(64);
    const powNonce = pow.solve(hashed_geo);
    return {
      hashed_geo,
      sender_token: 'token123',
      payload: Buffer.from('encrypted-gps-data').toString('base64'),
      ttl_seconds: 300,
      pow: powNonce,
    };
  };

  it('creates an envelope with valid data', async () => {
    const body = makeValidDrop();
    const res = await request(app).post('/api/drop').send(body);
    expect(res.status).toBe(201);
    expect(res.body.id).toBeDefined();
  });

  it('rejects missing hashed_geo', async () => {
    const body = makeValidDrop();
    delete body.hashed_geo;
    const res = await request(app).post('/api/drop').send(body);
    expect(res.status).toBe(400);
  });

  it('rejects invalid hashed_geo format', async () => {
    const body = makeValidDrop();
    body.hashed_geo = 'not-a-hex-hash';
    const res = await request(app).post('/api/drop').send(body);
    expect(res.status).toBe(400);
  });

  it('rejects missing proof-of-work', async () => {
    const body = makeValidDrop();
    delete body.pow;
    const res = await request(app).post('/api/drop').send(body);
    expect(res.status).toBe(429);
  });

  it('rejects invalid proof-of-work', async () => {
    const body = makeValidDrop();
    body.pow = 'invalid-nonce';
    const res = await request(app).post('/api/drop').send(body);
    expect(res.status).toBe(429);
  });
});

describe('POST /api/pickup', () => {
  it('returns matching envelopes', async () => {
    const hashed_geo = 'b'.repeat(64);
    const sender_token = 'friend-token-abc';
    const powNonce = pow.solve(hashed_geo);

    await request(app).post('/api/drop').send({
      hashed_geo,
      sender_token,
      payload: Buffer.from('test-payload').toString('base64'),
      ttl_seconds: 300,
      pow: powNonce,
    });

    const res = await request(app).post('/api/pickup').send({
      hashed_geos: [hashed_geo],
      known_tokens: [sender_token],
    });

    expect(res.status).toBe(200);
    expect(res.body.envelopes).toHaveLength(1);
    expect(res.body.envelopes[0].sender_token).toBe(sender_token);
  });

  it('returns empty for non-matching tokens', async () => {
    const hashed_geo = 'c'.repeat(64);
    const powNonce = pow.solve(hashed_geo);

    await request(app).post('/api/drop').send({
      hashed_geo,
      sender_token: 'real-token',
      payload: Buffer.from('data').toString('base64'),
      ttl_seconds: 300,
      pow: powNonce,
    });

    const res = await request(app).post('/api/pickup').send({
      hashed_geos: [hashed_geo],
      known_tokens: ['wrong-token'],
    });

    expect(res.status).toBe(200);
    expect(res.body.envelopes).toHaveLength(0);
  });

  it('rejects oversized hashed_geos array', async () => {
    const res = await request(app).post('/api/pickup').send({
      hashed_geos: Array(28).fill('a'.repeat(64)),
      known_tokens: ['token'],
    });
    expect(res.status).toBe(400);
    expect(res.body.error).toMatch(/limited to 27/);
  });

  it('rejects oversized known_tokens array', async () => {
    const res = await request(app).post('/api/pickup').send({
      hashed_geos: ['a'.repeat(64)],
      known_tokens: Array(51).fill('token'),
    });
    expect(res.status).toBe(400);
    expect(res.body.error).toMatch(/limited to 50/);
  });
});

describe('DELETE /api/sweep', () => {
  it('cleans up expired envelopes', async () => {
    // Insert an already-expired envelope directly
    db.prepare(
      'INSERT INTO envelopes (id, hashed_geo, sender_token, payload, expires_at) VALUES (?, ?, ?, ?, ?)'
    ).run('expired-1', 'd'.repeat(64), 'token', Buffer.from('old'), 0);

    const res = await request(app).delete('/api/sweep');
    expect(res.status).toBe(200);
    expect(res.body.swept).toBeGreaterThanOrEqual(1);
  });
});

describe('ProofOfWork', () => {
  it('solve produces a valid nonce', () => {
    const challenge = 'test-challenge';
    const nonce = pow.solve(challenge);
    expect(pow.verify(challenge, nonce)).toBe(true);
  });

  it('rejects invalid nonces', () => {
    expect(pow.verify('challenge', 'definitely-wrong')).toBe(false);
  });
});
