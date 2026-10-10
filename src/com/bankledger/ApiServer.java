package com.bankledger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** REST layer using the JDK's built-in HTTP server (no framework needed). */
public class ApiServer {
    private static final BankService bank = new BankService();

    @FunctionalInterface
    private interface Action {
        String run(Map<String, String> body) throws Exception;
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(16));

        server.createContext("/register", ex -> post(ex, 201, b -> {
            int accountId = bank.register(b.get("name"), b.get("email"), b.get("password"));
            return "{\"message\":\"registered\",\"accountId\":" + accountId + "}";
        }));

        server.createContext("/login", ex -> post(ex, 200, b -> {
            int accountId = bank.login(b.get("email"), b.get("password"));
            return "{\"message\":\"login successful\",\"accountId\":" + accountId + "}";
        }));

        server.createContext("/deposit", ex -> post(ex, 200, b -> {
            BigDecimal newBalance = bank.deposit(intOf(b, "accountId"), amountOf(b));
            return "{\"message\":\"deposit successful\",\"balance\":" + newBalance.toPlainString() + "}";
        }));

        server.createContext("/transfer", ex -> post(ex, 200, b -> {
            bank.transfer(intOf(b, "fromAccount"), intOf(b, "toAccount"), amountOf(b));
            return "{\"message\":\"transfer successful\"}";
        }));

        // GET /accounts/{id}/balance   and   GET /accounts/{id}/transactions
        server.createContext("/accounts/", ex -> {
            try {
                if (!ex.getRequestMethod().equals("GET")) {
                    throw new BankException(405, "use GET");
                }
                Matcher m = Pattern.compile("^/accounts/(\\d+)/(balance|transactions)$")
                                   .matcher(ex.getRequestURI().getPath());
                if (!m.matches()) {
                    throw new BankException(404, "unknown endpoint");
                }
                int id = Integer.parseInt(m.group(1));
                if (m.group(2).equals("balance")) {
                    respond(ex, 200, "{\"accountId\":" + id + ",\"balance\":"
                            + bank.getBalance(id).toPlainString() + "}");
                } else {
                    List<LedgerEntry> list = bank.getHistory(id);
                    StringBuilder sb = new StringBuilder("[");
                    for (int i = 0; i < list.size(); i++) {
                        if (i > 0) sb.append(",");
                        sb.append(list.get(i).toJson());
                    }
                    respond(ex, 200, sb.append("]").toString());
                }
            } catch (Exception e) {
                handleError(ex, e);
            }
        });

        server.start();
        System.out.println("Banking Ledger API running on http://localhost:" + port);
    }

    // ---------- helpers ----------

    private static void post(HttpExchange ex, int okStatus, Action action) throws IOException {
        try {
            if (!ex.getRequestMethod().equals("POST")) {
                throw new BankException(405, "use POST");
            }
            String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(ex, okStatus, action.run(parseFlatJson(raw)));
        } catch (Exception e) {
            handleError(ex, e);
        }
    }

    private static void handleError(HttpExchange ex, Exception e) throws IOException {
        if (e instanceof BankException) {
            respond(ex, ((BankException) e).getStatus(), "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        } else if (e instanceof SQLException) {
            e.printStackTrace();
            respond(ex, 500, "{\"error\":\"database error\"}");
        } else {
            respond(ex, 400, "{\"error\":\"invalid request\"}");
        }
    }

    private static void respond(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Parses a flat JSON object such as {"a":"x","b":12.5} into a HashMap. */
    private static Map<String, String> parseFlatJson(String json) {
        Map<String, String> map = new HashMap<>();
        Matcher m = Pattern.compile("\"(\\w+)\"\\s*:\\s*(?:\"([^\"]*)\"|(-?[0-9.]+))").matcher(json);
        while (m.find()) {
            map.put(m.group(1), m.group(2) != null ? m.group(2) : m.group(3));
        }
        return map;
    }

    private static int intOf(Map<String, String> b, String key) throws BankException {
        try {
            return Integer.parseInt(b.get(key));
        } catch (NumberFormatException e) {
            throw new BankException(400, key + " must be a number");
        }
    }

    private static BigDecimal amountOf(Map<String, String> b) throws BankException {
        try {
            return new BigDecimal(b.get("amount"));
        } catch (NumberFormatException | NullPointerException e) {
            throw new BankException(400, "amount must be a number");
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
