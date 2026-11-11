'use strict';

// Moonetix postback handler for Node.js 22. No dependencies.
// Postback URL:
// https://yoursite.com/postback?user={user_id}&tx={transaction_id}&event={event_id}&status={status}&reward={reward}&payout={payout}&hash={hash}

const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const { DatabaseSync } = require('node:sqlite');

const MICROS = 1000000;

// "12.5" -> 12500000. Exact, no floats.
function toMicros(reward) {
  const m = /^(\d{1,9})(?:\.(\d{1,6}))?$/.exec(reward);
  if (!m) return null;
  return Number(m[1]) * MICROS + Number((m[2] || '').padEnd(6, '0'));
}

function openDb(file) {
  fs.mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
  const db = new DatabaseSync(file);
  db.exec(`
    PRAGMA journal_mode = WAL;
    PRAGMA busy_timeout = 5000;
    CREATE TABLE IF NOT EXISTS moonetix_transactions (
      transaction_id TEXT PRIMARY KEY,
      user_id TEXT NOT NULL,
      reward_micros INTEGER NOT NULL,
      status INTEGER NOT NULL,
      updated_at TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS balances (
      user_id TEXT PRIMARY KEY,
      balance_micros INTEGER NOT NULL DEFAULT 0
    );
  `);
  return db;
}

// Applies one verified postback in one database transaction. Returns what happened.
function apply(db, tx, userId, micros, status) {
  const now = new Date().toISOString();
  db.exec('BEGIN IMMEDIATE');
  try {
    let result;
    if (status === 1) {
      // The primary key makes sure a transaction is credited only once.
      const ins = db.prepare(`INSERT INTO moonetix_transactions (transaction_id, user_id, reward_micros, status, updated_at)
        VALUES (?, ?, ?, 1, ?) ON CONFLICT (transaction_id) DO NOTHING`).run(tx, userId, micros, now);
      if (ins.changes === 1) {
        db.prepare(`INSERT INTO balances (user_id, balance_micros) VALUES (?, ?)
          ON CONFLICT (user_id) DO UPDATE SET balance_micros = balance_micros + excluded.balance_micros`).run(userId, micros);
        result = 'credited';
      } else {
        result = 'duplicate';
      }
    } else {
      const row = db.prepare('SELECT user_id, reward_micros, status FROM moonetix_transactions WHERE transaction_id = ?').get(tx);
      if (row && row.status === 1) {
        // Take back exactly what was credited, from the user who got it.
        db.prepare('UPDATE moonetix_transactions SET status = 2, updated_at = ? WHERE transaction_id = ?').run(now, tx);
        db.prepare('UPDATE balances SET balance_micros = balance_micros - ? WHERE user_id = ?').run(row.reward_micros, row.user_id);
        result = 'reversed';
      } else if (row) {
        result = 'duplicate';
      } else {
        // Never credited: remember it, so a late credit for this transaction is not paid.
        db.prepare(`INSERT INTO moonetix_transactions (transaction_id, user_id, reward_micros, status, updated_at)
          VALUES (?, ?, 0, 2, ?)`).run(tx, userId, now);
        result = 'ignored';
      }
    }
    db.exec('COMMIT');
    return result;
  } catch (err) {
    db.exec('ROLLBACK');
    throw err;
  }
}

function send(res, code, body) {
  res.writeHead(code, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(body);
}

function createHandler({ key, db }) {
  if (!key) throw new Error('MOONETIX_POSTBACK_KEY is not set');
  return (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname !== '/postback') return send(res, 404, 'Not found');
    if (req.method !== 'GET') {
      res.setHeader('Allow', 'GET');
      return send(res, 405, 'Method not allowed');
    }

    // 1. Check the signature before anything else. Values exactly as received.
    const q = url.searchParams;
    const tx = q.get('tx') ?? '';
    const userId = q.get('user') ?? '';
    const reward = q.get('reward') ?? '';
    const status = q.get('status') ?? '';
    const hash = q.get('hash') ?? '';
    if (!/^[0-9a-fA-F]{64}$/.test(hash)) return send(res, 400, 'Missing hash');
    const expected = crypto.createHmac('sha256', key).update(`${tx}:${userId}:${reward}:${status}`).digest('hex');
    if (!crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(hash))) return send(res, 403, 'Invalid hash');

    // 2. Signed, now check the values.
    const micros = toMicros(reward);
    if ((status !== '1' && status !== '2') || micros === null
      || !/^[A-Za-z0-9._@:+-]{1,100}$/.test(userId) || tx === '' || tx.length > 191) {
      return send(res, 400, 'Bad parameters');
    }

    // 3. Store it, then answer. Any error means 500, and Moonetix retries later.
    let result;
    try {
      result = apply(db, tx, userId, micros, Number(status));
    } catch (err) {
      console.error('moonetix: postback failed:', err.message);
      return send(res, 500, 'Error');
    }
    console.log(`moonetix: tx=${tx} status=${status} result=${result}`);
    send(res, 200, 'OK');
  };
}

module.exports = { createHandler, openDb, toMicros };

if (require.main === module) {
  const key = process.env.MOONETIX_POSTBACK_KEY;
  if (!key) {
    console.error('Set MOONETIX_POSTBACK_KEY first.');
    process.exit(1);
  }
  const db = openDb(process.env.DB_PATH || path.join(__dirname, 'data', 'moonetix.sqlite'));
  const port = Number(process.env.PORT || 8080);
  const host = process.env.HOST || '127.0.0.1';
  http.createServer(createHandler({ key, db })).listen(port, host, () => {
    console.log(`Moonetix postback handler on http://${host}:${port}/postback`);
  });
}
