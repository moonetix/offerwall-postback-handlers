# Java handler

Java 17 or newer, JDK only (`com.sun.net.httpserver`, `javax.crypto`). One file: [Handler.java](Handler.java).

## Run

```sh
export MOONETIX_POSTBACK_KEY=your_postback_secret_key
java Handler.java
```

It listens on `http://127.0.0.1:8080/postback`. Set `HOST` and `PORT` to change that. Put a reverse proxy with HTTPS in front of it.

## Swap in your database

`MemoryStore` keeps balances in memory and loses them on restart. It shows the rules, nothing more. Before you go live, implement `Handler.Store` with your database:

- `credit`: in one transaction, insert `transaction_id` into a table where it is the primary key. Add the reward to the balance only if the insert worked.
- `reverse`: in one transaction, lock the row (`SELECT ... FOR UPDATE`). If its status is 1, set it to 2 and subtract the stored amount from the stored user. If there is no row, insert one with status 2 and amount 0.
- Return only after the commit. If anything fails, throw: the handler answers 500 and Moonetix retries.

Use `BigDecimal` or `DECIMAL` columns for rewards, never `double`.

## Test

```sh
./test.sh
```

Compiles `Handler.java` into `build/` and sends the shared postbacks in [../testdata/postbacks.json](../testdata/postbacks.json).
