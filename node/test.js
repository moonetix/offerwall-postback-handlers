'use strict';

// Runs the shared postbacks in ../testdata/postbacks.json against the handler
// and checks the answers and the balances. Usage: npm test

const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const { createHandler, openDb, toMicros } = require('./server.js');

const data = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'testdata', 'postbacks.json'), 'utf8'));
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'moonetix-node-test-'));
const db = openDb(path.join(dir, 'test.sqlite'));
console.log = () => {}; // Keep the handler's log lines out of the test output.

function balance(user) {
  const row = db.prepare('SELECT balance_micros FROM balances WHERE user_id = ?').get(user);
  return row ? row.balance_micros : 0;
}

async function main() {
  const server = http.createServer(createHandler({ key: data.key, db }));
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  let pass = 0;
  let fail = 0;
  for (const c of data.cases) {
    const responses = await Promise.all(Array.from({ length: c.parallel || 1 }, async () => {
      const r = await fetch(base + c.path);
      return { code: r.status, body: await r.text() };
    }));
    const errors = new Set();
    for (const r of responses) {
      if (r.code !== c.expect_status) errors.add(`HTTP ${r.code}, expected ${c.expect_status}`);
      else if (r.code === 200 && r.body !== 'OK') errors.add(`body '${r.body}', expected 'OK'`);
    }
    for (const [user, want] of Object.entries(c.expect_balances)) {
      const got = balance(user);
      if (got !== toMicros(want)) errors.add(`balance of ${user} is ${got / 1e6}, expected ${want}`);
    }
    if (errors.size) {
      fail++;
      process.stdout.write(`FAIL  ${c.name}: ${[...errors].join('; ')}\n`);
    } else {
      pass++;
      process.stdout.write(`ok    ${c.name}\n`);
    }
  }
  process.stdout.write(`\n${pass} passed, ${fail} failed\n`);
  server.close();
  db.close();
  fs.rmSync(dir, { recursive: true, force: true });
  process.exitCode = fail ? 1 : 0;
}

main().catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
