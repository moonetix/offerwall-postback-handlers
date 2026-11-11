<?php

declare(strict_types=1);

// Moonetix postback handler. Postback URL:
// https://yoursite.com/postback.php?user={user_id}&tx={transaction_id}&event={event_id}&status={status}&reward={reward}&payout={payout}&hash={hash}

const MICROS = 1000000;

function reply(int $code, string $body): never
{
    http_response_code($code);
    header('Content-Type: text/plain; charset=utf-8');
    header('Cache-Control: no-store');
    echo $body;
    exit;
}

function param(string $name): string
{
    $v = $_GET[$name] ?? '';
    return is_string($v) ? $v : '';
}

// "12.5" -> 12500000. Exact, no floats.
function to_micros(string $reward): ?int
{
    if (!preg_match('/^(\d{1,9})(?:\.(\d{1,6}))?$/', $reward, $m)) {
        return null;
    }
    return (int)$m[1] * MICROS + (int)str_pad($m[2] ?? '', 6, '0');
}

function db(): PDO
{
    $path = getenv('DB_PATH') ?: dirname(__DIR__) . '/data/moonetix.sqlite';
    if (!is_dir(dirname($path))) {
        mkdir(dirname($path), 0700, true);
    }
    $pdo = new PDO('sqlite:' . $path, null, null, [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_TIMEOUT => 5]);
    $pdo->exec('PRAGMA journal_mode = WAL');
    $pdo->exec('CREATE TABLE IF NOT EXISTS moonetix_transactions (
        transaction_id TEXT PRIMARY KEY,
        user_id TEXT NOT NULL,
        reward_micros INTEGER NOT NULL,
        status INTEGER NOT NULL,
        updated_at TEXT NOT NULL
    )');
    $pdo->exec('CREATE TABLE IF NOT EXISTS balances (
        user_id TEXT PRIMARY KEY,
        balance_micros INTEGER NOT NULL DEFAULT 0
    )');
    return $pdo;
}

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'GET') {
    header('Allow: GET');
    reply(405, 'Method not allowed');
}

$key = getenv('MOONETIX_POSTBACK_KEY') ?: '';
if ($key === '') {
    error_log('moonetix: MOONETIX_POSTBACK_KEY is not set');
    reply(500, 'Not configured');
}

// 1. Check the signature before anything else. Values exactly as received.
$tx = param('tx');
$userId = param('user');
$reward = param('reward');
$status = param('status');
$hash = param('hash');
if (!preg_match('/^[0-9a-fA-F]{64}$/', $hash)) {
    reply(400, 'Missing hash');
}
$expected = hash_hmac('sha256', $tx . ':' . $userId . ':' . $reward . ':' . $status, $key);
if (!hash_equals($expected, $hash)) {
    reply(403, 'Invalid hash');
}

// 2. Signed, now check the values.
$micros = to_micros($reward);
if (!in_array($status, ['1', '2'], true)
    || $micros === null
    || !preg_match('/^[A-Za-z0-9._@:+-]{1,100}$/', $userId)
    || $tx === '' || strlen($tx) > 191) {
    reply(400, 'Bad parameters');
}

// 3. Apply it once, inside one database transaction.
try {
    $db = db();
    $db->exec('BEGIN IMMEDIATE'); // Takes the write lock now, so parallel requests wait their turn.
    $open = true;
    $now = date('c');
    if ($status === '1') {
        // The primary key makes sure a transaction is credited only once, even with parallel retries.
        $ins = $db->prepare('INSERT INTO moonetix_transactions (transaction_id, user_id, reward_micros, status, updated_at) VALUES (?, ?, ?, 1, ?) ON CONFLICT (transaction_id) DO NOTHING');
        $ins->execute([$tx, $userId, $micros, $now]);
        if ($ins->rowCount() === 1) {
            $db->prepare('INSERT INTO balances (user_id, balance_micros) VALUES (?, ?) ON CONFLICT (user_id) DO UPDATE SET balance_micros = balance_micros + excluded.balance_micros')
                ->execute([$userId, $micros]);
            $result = 'credited';
        } else {
            $result = 'duplicate';
        }
    } else {
        $sel = $db->prepare('SELECT user_id, reward_micros, status FROM moonetix_transactions WHERE transaction_id = ?');
        $sel->execute([$tx]);
        $row = $sel->fetch(PDO::FETCH_ASSOC);
        if ($row && (int)$row['status'] === 1) {
            // Take back exactly what was credited, from the user who got it.
            $db->prepare('UPDATE moonetix_transactions SET status = 2, updated_at = ? WHERE transaction_id = ?')->execute([$now, $tx]);
            $db->prepare('UPDATE balances SET balance_micros = balance_micros - ? WHERE user_id = ?')->execute([(int)$row['reward_micros'], $row['user_id']]);
            $result = 'reversed';
        } elseif ($row) {
            $result = 'duplicate';
        } else {
            // Never credited: remember it, so a late credit for this transaction is not paid.
            $db->prepare('INSERT INTO moonetix_transactions (transaction_id, user_id, reward_micros, status, updated_at) VALUES (?, ?, 0, 2, ?)')
                ->execute([$tx, $userId, $now]);
            $result = 'ignored';
        }
    }
    $db->exec('COMMIT');
    $open = false;
} catch (Throwable $e) {
    if (!empty($open)) {
        $db->exec('ROLLBACK');
    }
    error_log('moonetix: postback failed: ' . $e->getMessage());
    reply(500, 'Error'); // Moonetix retries later.
}

error_log("moonetix: tx=$tx status=$status result=$result");
reply(200, 'OK');
