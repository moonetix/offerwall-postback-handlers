"""Moonetix postback handler for Python 3. Standard library only.

Postback URL:
https://yoursite.com/postback?user={user_id}&tx={transaction_id}&event={event_id}&status={status}&reward={reward}&payout={payout}&hash={hash}
"""

import hashlib
import hmac
import os
import re
import sqlite3
import sys
from contextlib import closing
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

MICROS = 1_000_000
HASH_RE = re.compile(r"[0-9a-fA-F]{64}")
REWARD_RE = re.compile(r"([0-9]{1,9})(?:\.([0-9]{1,6}))?")
USER_RE = re.compile(r"[A-Za-z0-9._@:+-]{1,100}")

SCHEMA = """
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
"""


def to_micros(reward):
    """'12.5' -> 12500000. Exact, no floats."""
    m = REWARD_RE.fullmatch(reward)
    if not m:
        return None
    return int(m.group(1)) * MICROS + int((m.group(2) or "").ljust(6, "0"))


def connect(db_path):
    conn = sqlite3.connect(db_path, timeout=5, isolation_level=None)
    conn.execute("PRAGMA journal_mode = WAL")
    return conn


def init_db(db_path):
    os.makedirs(os.path.dirname(os.path.abspath(db_path)), mode=0o700, exist_ok=True)
    with closing(connect(db_path)) as conn:
        conn.executescript(SCHEMA)


def apply(conn, tx, user_id, micros, status):
    """Applies one verified postback in one database transaction. Returns what happened."""
    now = datetime.now(timezone.utc).isoformat()
    conn.execute("BEGIN IMMEDIATE")  # Takes the write lock now, so parallel requests wait their turn.
    try:
        if status == 1:
            # The primary key makes sure a transaction is credited only once.
            cur = conn.execute(
                "INSERT INTO moonetix_transactions (transaction_id, user_id, reward_micros, status, updated_at)"
                " VALUES (?, ?, ?, 1, ?) ON CONFLICT (transaction_id) DO NOTHING",
                (tx, user_id, micros, now),
            )
            if cur.rowcount == 1:
                conn.execute(
                    "INSERT INTO balances (user_id, balance_micros) VALUES (?, ?)"
                    " ON CONFLICT (user_id) DO UPDATE SET balance_micros = balance_micros + excluded.balance_micros",
                    (user_id, micros),
                )
                result = "credited"
            else:
                result = "duplicate"
        else:
            row = conn.execute(
                "SELECT user_id, reward_micros, status FROM moonetix_transactions WHERE transaction_id = ?", (tx,)
            ).fetchone()
            if row and row[2] == 1:
                # Take back exactly what was credited, from the user who got it.
                conn.execute(
                    "UPDATE moonetix_transactions SET status = 2, updated_at = ? WHERE transaction_id = ?", (now, tx)
                )
                conn.execute(
                    "UPDATE balances SET balance_micros = balance_micros - ? WHERE user_id = ?", (row[1], row[0])
                )
                result = "reversed"
            elif row:
                result = "duplicate"
            else:
                # Never credited: remember it, so a late credit for this transaction is not paid.
                conn.execute(
                    "INSERT INTO moonetix_transactions (transaction_id, user_id, reward_micros, status, updated_at)"
                    " VALUES (?, ?, 0, 2, ?)",
                    (tx, user_id, now),
                )
                result = "ignored"
        conn.execute("COMMIT")
        return result
    except BaseException:
        conn.execute("ROLLBACK")
        raise


def make_handler(key, db_path):
    if not key:
        raise ValueError("MOONETIX_POSTBACK_KEY is not set")
    secret = key.encode()

    class PostbackHandler(BaseHTTPRequestHandler):
        def send(self, code, body):
            data = body.encode()
            self.send_response(code)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(data)))
            if code == 405:
                self.send_header("Allow", "GET")
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            url = urlsplit(self.path)
            if url.path != "/postback":
                return self.send(404, "Not found")
            q = parse_qs(url.query, keep_blank_values=True)

            def param(name):
                return q.get(name, [""])[0]

            # 1. Check the signature before anything else. Values exactly as received.
            tx, user_id, reward, status, given = (param(n) for n in ("tx", "user", "reward", "status", "hash"))
            if not HASH_RE.fullmatch(given):
                return self.send(400, "Missing hash")
            expected = hmac.new(secret, f"{tx}:{user_id}:{reward}:{status}".encode(), hashlib.sha256).hexdigest()
            if not hmac.compare_digest(expected, given):
                return self.send(403, "Invalid hash")

            # 2. Signed, now check the values.
            micros = to_micros(reward)
            if status not in ("1", "2") or micros is None or not USER_RE.fullmatch(user_id) or not 0 < len(tx) <= 191:
                return self.send(400, "Bad parameters")

            # 3. Store it, then answer. Any error means 500, and Moonetix retries later.
            try:
                with closing(connect(db_path)) as conn:
                    result = apply(conn, tx, user_id, micros, int(status))
            except Exception as err:
                print(f"moonetix: postback failed: {err}", file=sys.stderr)
                return self.send(500, "Error")
            print(f"moonetix: tx={tx} status={status} result={result}", flush=True)
            self.send(200, "OK")

        def do_POST(self):
            self.send(405, "Method not allowed")

        do_PUT = do_DELETE = do_PATCH = do_POST

        def log_message(self, format, *args):
            pass  # The default log prints the full URL, hash included. We log one line per postback instead.

    return PostbackHandler


def main():
    key = os.environ.get("MOONETIX_POSTBACK_KEY", "")
    if not key:
        sys.exit("Set MOONETIX_POSTBACK_KEY first.")
    db_path = os.environ.get("DB_PATH") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "moonetix.sqlite")
    init_db(db_path)
    host = os.environ.get("HOST", "127.0.0.1")
    port = int(os.environ.get("PORT", "8080"))
    server = ThreadingHTTPServer((host, port), make_handler(key, db_path))
    print(f"Moonetix postback handler on http://{host}:{port}/postback", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
