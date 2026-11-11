"""Runs the shared postbacks in ../testdata/postbacks.json against the handler
and checks the answers and the balances. Usage: python3 test_handler.py
"""

import json
import os
import sqlite3
import sys
import tempfile
import threading
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import urlopen

import handler

HERE = os.path.dirname(os.path.abspath(__file__))


def fetch(url):
    try:
        with urlopen(url, timeout=10) as r:
            return r.status, r.read().decode()
    except HTTPError as e:
        return e.code, e.read().decode()


def main():
    with open(os.path.join(HERE, "..", "testdata", "postbacks.json")) as f:
        data = json.load(f)
    tmp = tempfile.TemporaryDirectory(prefix="moonetix-python-test-")
    db_path = os.path.join(tmp.name, "test.sqlite")
    handler.init_db(db_path)
    handler.print = lambda *a, **k: None  # Keep the handler's log lines out of the test output.
    server = ThreadingHTTPServer(("127.0.0.1", 0), handler.make_handler(data["key"], db_path))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{server.server_address[1]}"

    def balance(user):
        with closing(sqlite3.connect(db_path)) as conn:
            row = conn.execute("SELECT balance_micros FROM balances WHERE user_id = ?", (user,)).fetchone()
        return row[0] if row else 0

    passed = failed = 0
    for c in data["cases"]:
        n = c.get("parallel", 1)
        with ThreadPoolExecutor(max_workers=n) as pool:
            responses = list(pool.map(fetch, [base + c["path"]] * n))
        errors = []
        for code, body in responses:
            if code != c["expect_status"]:
                errors.append(f"HTTP {code}, expected {c['expect_status']}")
            elif code == 200 and body != "OK":
                errors.append(f"body '{body}', expected 'OK'")
        for user, want in c["expect_balances"].items():
            got = balance(user)
            if got != handler.to_micros(want):
                errors.append(f"balance of {user} is {got / 1e6}, expected {want}")
        if errors:
            failed += 1
            print(f"FAIL  {c['name']}: " + "; ".join(dict.fromkeys(errors)))
        else:
            passed += 1
            print(f"ok    {c['name']}")
    print(f"\n{passed} passed, {failed} failed")
    server.shutdown()
    server.server_close()
    tmp.cleanup()
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
