import com.sun.net.httpserver.HttpServer;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Runs the shared postbacks in ../testdata/postbacks.json against Handler. Usage: ./test.sh */
public class TestHandler {

    public static void main(String[] args) throws Exception {
        Map<?, ?> data = (Map<?, ?>) new Json(Files.readString(Path.of("..", "testdata", "postbacks.json"))).value();
        Handler.MemoryStore store = new Handler.MemoryStore();
        PrintStream out = System.out;
        System.setOut(new PrintStream(PrintStream.nullOutputStream())); // Keep the handler's log lines out of the test output.
        HttpServer server = Handler.start("127.0.0.1", 0, (String) data.get("key"), store);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        HttpClient client = HttpClient.newHttpClient();

        int pass = 0;
        int fail = 0;
        for (Object o : (List<?>) data.get("cases")) {
            Map<?, ?> c = (Map<?, ?>) o;
            int expect = ((Number) c.get("expect_status")).intValue();
            int n = c.containsKey("parallel") ? ((Number) c.get("parallel")).intValue() : 1;
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + c.get("path"))).GET().build();
            List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                calls.add(client.sendAsync(req, HttpResponse.BodyHandlers.ofString()));
            }
            Set<String> errors = new LinkedHashSet<>();
            for (CompletableFuture<HttpResponse<String>> call : calls) {
                HttpResponse<String> r = call.get();
                if (r.statusCode() != expect) {
                    errors.add("HTTP " + r.statusCode() + ", expected " + expect);
                } else if (r.statusCode() == 200 && !r.body().equals("OK")) {
                    errors.add("body '" + r.body() + "', expected 'OK'");
                }
            }
            for (Map.Entry<?, ?> e : ((Map<?, ?>) c.get("expect_balances")).entrySet()) {
                BigDecimal got = store.balance((String) e.getKey());
                if (got.compareTo(new BigDecimal((String) e.getValue())) != 0) {
                    errors.add("balance of " + e.getKey() + " is " + got.toPlainString() + ", expected " + e.getValue());
                }
            }
            if (errors.isEmpty()) {
                pass++;
                out.println("ok    " + c.get("name"));
            } else {
                fail++;
                out.println("FAIL  " + c.get("name") + ": " + String.join("; ", errors));
            }
        }
        out.println();
        out.println(pass + " passed, " + fail + " failed");
        server.stop(0);
        System.exit(fail == 0 ? 0 : 1);
    }

    /** Just enough JSON for the test data: objects, arrays, strings, integers. */
    static final class Json {
        private final String s;
        private int i;

        Json(String s) {
            this.s = s;
        }

        Object value() {
            skip();
            char ch = s.charAt(i);
            if (ch == '{') {
                Map<String, Object> m = new LinkedHashMap<>();
                i++;
                skip();
                if (s.charAt(i) == '}') {
                    i++;
                    return m;
                }
                do {
                    skip();
                    String k = string();
                    skip();
                    i++; // ':'
                    m.put(k, value());
                    skip();
                } while (s.charAt(i++) == ',');
                return m;
            }
            if (ch == '[') {
                List<Object> l = new ArrayList<>();
                i++;
                skip();
                if (s.charAt(i) == ']') {
                    i++;
                    return l;
                }
                do {
                    l.add(value());
                    skip();
                } while (s.charAt(i++) == ',');
                return l;
            }
            if (ch == '"') {
                return string();
            }
            int start = i;
            while (i < s.length() && "-0123456789".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            return Long.parseLong(s.substring(start, i));
        }

        private String string() {
            StringBuilder b = new StringBuilder();
            i++; // opening quote
            while (s.charAt(i) != '"') {
                char ch = s.charAt(i++);
                if (ch == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> b.append('\n');
                        case 't' -> b.append('\t');
                        case 'u' -> {
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> b.append(e);
                    }
                } else {
                    b.append(ch);
                }
            }
            i++;
            return b.toString();
        }

        private void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }
    }
}
