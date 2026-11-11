<?php

declare(strict_types=1);

// Runs the shared postbacks in ../testdata/postbacks.json against public/postback.php
// on PHP's built-in server and checks the answers and the balances. Usage: php test.php

$data = json_decode((string)file_get_contents(__DIR__ . '/../testdata/postbacks.json'), true);
$dir = sys_get_temp_dir() . '/moonetix-php-test-' . bin2hex(random_bytes(4));
mkdir($dir, 0700);
$dbPath = $dir . '/test.sqlite';

$sock = stream_socket_server('tcp://127.0.0.1:0');
$port = (int)substr(strrchr(stream_socket_get_name($sock, false), ':'), 1);
fclose($sock);

// 4 workers, so the parallel case really runs in parallel.
$env = array_merge(getenv(), ['MOONETIX_POSTBACK_KEY' => $data['key'], 'DB_PATH' => $dbPath, 'PHP_CLI_SERVER_WORKERS' => '4']);
$server = proc_open([PHP_BINARY, '-S', "127.0.0.1:$port", '-t', __DIR__ . '/public'], [1 => ['file', '/dev/null', 'w'], 2 => ['file', $dir . '/server.log', 'w']], $pipes, null, $env);
register_shutdown_function(function () use ($server, $dir) {
    proc_terminate($server);
    proc_close($server);
    array_map('unlink', glob($dir . '/*'));
    rmdir($dir);
});
for ($i = 0; $i < 50 && !@fsockopen('127.0.0.1', $port); $i++) {
    usleep(100000);
}

function micros(string $v): int
{
    [$int, $frac] = explode('.', $v . '.');
    return (int)$int * 1000000 + (int)str_pad($frac, 6, '0');
}

function balance(string $dbPath, string $user): int
{
    if (!is_file($dbPath)) {
        return 0;
    }
    $st = (new PDO('sqlite:' . $dbPath))->prepare('SELECT balance_micros FROM balances WHERE user_id = ?');
    $st->execute([$user]);
    return (int)$st->fetchColumn();
}

$base = "http://127.0.0.1:$port";
$pass = 0;
$fail = 0;
foreach ($data['cases'] as $c) {
    $url = $base . preg_replace('#^/postback\?#', '/postback.php?', $c['path']);
    $n = $c['parallel'] ?? 1;
    $mh = curl_multi_init();
    $handles = [];
    for ($i = 0; $i < $n; $i++) {
        $h = curl_init($url);
        curl_setopt_array($h, [CURLOPT_RETURNTRANSFER => true, CURLOPT_TIMEOUT => 10]);
        curl_multi_add_handle($mh, $h);
        $handles[] = $h;
    }
    do {
        curl_multi_exec($mh, $running);
        curl_multi_select($mh);
    } while ($running > 0);
    $errors = [];
    foreach ($handles as $h) {
        $code = curl_getinfo($h, CURLINFO_RESPONSE_CODE);
        $body = curl_multi_getcontent($h);
        if ($code !== $c['expect_status']) {
            $errors[] = "HTTP $code, expected {$c['expect_status']}";
        } elseif ($code === 200 && $body !== 'OK') {
            $errors[] = "body '$body', expected 'OK'";
        }
    }
    foreach ($c['expect_balances'] as $user => $want) {
        $got = balance($dbPath, (string)$user);
        if ($got !== micros($want)) {
            $errors[] = "balance of $user is " . ($got / 1000000) . ", expected $want";
        }
    }
    if ($errors) {
        $fail++;
        echo "FAIL  {$c['name']}: " . implode('; ', array_unique($errors)) . "\n";
    } else {
        $pass++;
        echo "ok    {$c['name']}\n";
    }
}
echo "\n$pass passed, $fail failed\n";
exit($fail ? 1 : 0);
