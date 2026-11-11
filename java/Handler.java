import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Moonetix postback handler for Java 17 or newer. JDK only. Run: java Handler.java
 *
 * Postback URL:
 * https://yoursite.com/postback?user={user_id}&tx={transaction_id}&event={event_id}&status={status}&reward={reward}&payout={payout}&hash={hash}
 *
 * MemoryStore keeps everything in memory and loses it on restart. It is here to show the rules.
 * Before you go live, implement Store with your database (see README.md).
 */
public class Handler {

    private static final Pattern HASH = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern REWARD = Pattern.compile("[0-9]{1,9}(\\.[0-9]{1,6})?");
    private static final Pattern USER = Pattern.compile("[A-Za-z0-9._@:+-]{1,100}");

    /** Where credits and balances live. Each method must be atomic and durable before it returns. */
    public interface Store {
        /** Credits the reward once per transaction ID. Returns "credited" or "duplicate". */
        String credit(String tx, String userId, BigDecimal reward);

        /** Takes back what was credited for the transaction. Returns "reversed", "duplicate", or "ignored". */
        String reverse(String tx, String userId);

        BigDecimal balance(String userId);
    }

    /** In-memory store for trying things out. Swap in your database before going live. */
    public static final class MemoryStore implements Store {
        private record Tx(String userId, BigDecimal reward, int status) {}

        private final Map<String, Tx> transactions = new HashMap<>();
        private final Map<String, BigDecimal> balances = new HashMap<>();

        @Override
        public synchronized String credit(String tx, String userId, BigDecimal reward) {
            if (transactions.containsKey(tx)) {
                return "duplicate";
            }
            transactions.put(tx, new Tx(userId, reward, 1));
            balances.merge(userId, reward, BigDecimal::add);
            return "credited";
        }

        @Override
        public synchronized String reverse(String tx, String userId) {
            Tx t = transactions.get(tx);
            if (t == null) {
                // Never credited: remember it, so a late credit for this transaction is not paid.
                transactions.put(tx, new Tx(userId, BigDecimal.ZERO, 2));
                return "ignored";
            }
            if (t.status() != 1) {
                return "duplicate";
            }
            // Take back exactly what was credited, from the user who got it.
            transactions.put(tx, new Tx(t.userId(), t.reward(), 2));
            balances.merge(t.userId(), t.reward().negate(), BigDecimal::add);
            return "reversed";
        }

        @Override
        public synchronized BigDecimal balance(String userId) {
            return balances.getOrDefault(userId, BigDecimal.ZERO);
        }
    }

    public static HttpServer start(String host, int port, String key, Store store) throws IOException {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("MOONETIX_POSTBACK_KEY is not set");
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/postback", ex -> {
            try {
                handle(ex, key, store);
            } catch (Exception e) {
                System.err.println("moonetix: postback failed: " + e);
                try {
                    send(ex, 500, "Error"); // Moonetix retries later.
                } catch (IOException ignored) {
                    // The answer was already sent or the connection is gone.
                }
            } finally {
                ex.close();
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        return server;
    }

    private static void handle(HttpExchange ex, String key, Store store) throws Exception {
        if (!ex.getRequestURI().getPath().equals("/postback")) {
            send(ex, 404, "Not found");
            return;
        }
        if (!ex.getRequestMethod().equals("GET")) {
            ex.getResponseHeaders().set("Allow", "GET");
            send(ex, 405, "Method not allowed");
            return;
        }

        // 1. Check the signature before anything else. Values exactly as received.
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        String tx = q.getOrDefault("tx", "");
        String userId = q.getOrDefault("user", "");
        String reward = q.getOrDefault("reward", "");
        String status = q.getOrDefault("status", "");
        String hash = q.getOrDefault("hash", "");
        if (!HASH.matcher(hash).matches()) {
            send(ex, 400, "Missing hash");
            return;
        }
        String expected = hmacSha256(key, tx + ":" + userId + ":" + reward + ":" + status);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), hash.getBytes(StandardCharsets.US_ASCII))) {
            send(ex, 403, "Invalid hash");
            return;
        }

        // 2. Signed, now check the values.
        if (!(status.equals("1") || status.equals("2")) || !REWARD.matcher(reward).matches()
                || !USER.matcher(userId).matches() || tx.isEmpty() || tx.length() > 191) {
            send(ex, 400, "Bad parameters");
            return;
        }

        // 3. Store it, then answer. An exception here becomes a 500, and Moonetix retries later.
        String result = status.equals("1")
                ? store.credit(tx, userId, new BigDecimal(reward))
                : store.reverse(tx, userId);
        System.out.println("moonetix: tx=" + tx + " status=" + status + " result=" + result);
        send(ex, 200, "OK");
    }

    /** First value of each parameter, URL-decoded. A broken query gives an empty map, so the request fails with 400. */
    private static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) {
            return out;
        }
        try {
            for (String pair : raw.split("&")) {
                int i = pair.indexOf('=');
                String name = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
                String value = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
                out.putIfAbsent(name, value);
            }
        } catch (IllegalArgumentException e) {
            out.clear();
        }
        return out;
    }

    private static String hmacSha256(String key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder hex = new StringBuilder(64);
        for (byte b : mac.doFinal(data.getBytes(StandardCharsets.UTF_8))) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static void main(String[] args) throws IOException {
        String key = System.getenv("MOONETIX_POSTBACK_KEY");
        if (key == null || key.isEmpty()) {
            System.err.println("Set MOONETIX_POSTBACK_KEY first.");
            System.exit(1);
        }
        String host = System.getenv().getOrDefault("HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        start(host, port, key, new MemoryStore());
        System.out.println("Moonetix postback handler on http://" + host + ":" + port + "/postback");
        System.out.println("Note: MemoryStore loses all balances on restart. Use your database before going live.");
    }
}
