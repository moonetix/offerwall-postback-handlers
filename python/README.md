# Python handler

Python 3.8 or newer, standard library only (`http.server`, `sqlite3`, `hmac`). One file: [handler.py](handler.py).

## Run

```sh
export MOONETIX_POSTBACK_KEY=your_postback_secret_key
python3 handler.py
```

It listens on `http://127.0.0.1:8080/postback`. Set `HOST`, `PORT`, and `DB_PATH` to change that. Put a reverse proxy with HTTPS in front of it.

## Use it in Flask or Django

The money logic is in `apply(conn, tx, user_id, micros, status)`. Keep the hash check from `do_GET`, then call `apply()` with your own connection, or port its SQL to your database.

## Test

```sh
python3 test_handler.py
```

Sends the shared postbacks in [../testdata/postbacks.json](../testdata/postbacks.json) and checks the answers and the balances.
