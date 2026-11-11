# Node.js handler

Node.js 22.5 or newer, with the built-in `node:http` and `node:sqlite`. No npm packages. One file: [server.js](server.js).

## Run

```sh
export MOONETIX_POSTBACK_KEY=your_postback_secret_key
npm start
```

It listens on `http://127.0.0.1:8080/postback`. Set `HOST`, `PORT`, and `DB_PATH` to change that. Put a reverse proxy with HTTPS in front of it.

`node:sqlite` still prints an ExperimentalWarning on Node 22, so `npm start` runs Node with `--disable-warning=ExperimentalWarning`. You can also load the key from a file: `node --env-file=../.env --disable-warning=ExperimentalWarning server.js`.

## Use your own database

`apply()` holds all the money logic in one transaction. Port it to your database: insert `transaction_id` into a table where it is the primary key, add to the balance only if the insert worked, and on status 2 take back the stored amount once.

## Test

```sh
npm test
```

Sends the shared postbacks in [../testdata/postbacks.json](../testdata/postbacks.json) and checks the answers and the balances.
