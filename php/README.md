# PHP handler

Plain PHP 8.1 or newer with PDO SQLite. One file: [public/postback.php](public/postback.php).

## Run

```sh
export MOONETIX_POSTBACK_KEY=your_postback_secret_key
php -S 127.0.0.1:8080 -t public
```

The built-in server is for trying it out. In production, serve `public/` with nginx or Apache and PHP-FPM, and pass the key as an environment variable:

- PHP-FPM pool: `env[MOONETIX_POSTBACK_KEY] = your_postback_secret_key`
- Apache: `SetEnv MOONETIX_POSTBACK_KEY your_postback_secret_key`

Postback URL: `https://yoursite.com/postback.php?user={user_id}&tx={transaction_id}&event={event_id}&status={status}&reward={reward}&payout={payout}&hash={hash}`

The database goes to `data/moonetix.sqlite`, next to `public/` and outside the web root. Set `DB_PATH` to put it elsewhere. The web server user needs write access to that folder.

## Use your own database

Keep the two tables or map them to yours: `moonetix_transactions` (primary key `transaction_id`) and your users' balance column. With MySQL, use `INSERT IGNORE` or `ON DUPLICATE KEY` for the credit and `SELECT ... FOR UPDATE` before a reversal, all in one transaction.

## Test

```sh
php test.php
```

Needs the `pdo_sqlite` and `curl` extensions. It starts the built-in server with 4 workers and sends the shared postbacks in [../testdata/postbacks.json](../testdata/postbacks.json).
