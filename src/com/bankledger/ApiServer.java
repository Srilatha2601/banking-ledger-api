
package com.bankledger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** REST API server and frontend hosting. */
public class ApiServer {

    private static final BankService bank = new BankService();

    @FunctionalInterface
    private interface Action {
        String run(Map<String, String> body) throws Exception;
    }

    public static void main(String[] args) throws IOException {

        int port = Integer.parseInt(
                System.getenv().getOrDefault("PORT", "8080"));

        HttpServer server = HttpServer.create(
                new InetSocketAddress(port), 0);

        server.setExecutor(
                java.util.concurrent.Executors.newFixedThreadPool(16));

        // Registration
        server.createContext("/register", ex -> post(ex, 201, b -> {
            int accountId = bank.register(
                    b.get("name"),
                    b.get("email"),
                    b.get("password"));

            return "{\"message\":\"registered\",\"accountId\":"
                    + accountId + "}";
        }));

        // Login
        server.createContext("/login", ex -> post(ex, 200, b -> {
            int accountId = bank.login(
                    b.get("email"),
                    b.get("password"));

            return "{\"message\":\"login successful\",\"accountId\":"
                    + accountId + "}";
        }));

        // Deposit
        server.createContext("/deposit", ex -> post(ex, 200, b -> {
            BigDecimal amount = amountOf(b);

            if (amount.signum() <= 0) {
                throw new BankException(400,
                        "amount must be greater than zero");
            }

            BigDecimal newBalance = bank.deposit(
                    intOf(b, "accountId"), amount);

            return "{\"message\":\"deposit successful\",\"balance\":"
                    + newBalance.toPlainString() + "}";
        }));

        // Withdrawal
        server.createContext("/withdraw", ex -> post(ex, 200, b -> {
            BigDecimal amount = amountOf(b);

            if (amount.signum() <= 0) {
                throw new BankException(400,
                        "amount must be greater than zero");
            }

            // This method must exist in BankService.java.
            // Its signature should be:
            // BigDecimal withdraw(int accountId, BigDecimal amount)
            BigDecimal newBalance = bank.withdraw(
                    intOf(b, "accountId"), amount);

            return "{\"message\":\"withdrawal successful\",\"balance\":"
                    + newBalance.toPlainString() + "}";
        }));

        // Transfer
        server.createContext("/transfer", ex -> post(ex, 200, b -> {
            BigDecimal amount = amountOf(b);

            if (amount.signum() <= 0) {
                throw new BankException(400,
                        "amount must be greater than zero");
            }

            bank.transfer(
                    intOf(b, "fromAccount"),
                    intOf(b, "toAccount"),
                    amount);

            return "{\"message\":\"transfer successful\"}";
        }));

        // Account balance and transaction history
        server.createContext("/accounts/", ex -> {
            try {
                if (!ex.getRequestMethod().equals("GET")) {
                    throw new BankException(405, "use GET");
                }

                Matcher matcher = Pattern.compile(
                        "^/accounts/(\\d+)/(balance|transactions)$")
                        .matcher(ex.getRequestURI().getPath());

                if (!matcher.matches()) {
                    throw new BankException(404,
                            "unknown endpoint");
                }

                int id = Integer.parseInt(matcher.group(1));

                if (matcher.group(2).equals("balance")) {
                    respond(ex, 200,
                            "{\"accountId\":" + id
                                    + ",\"balance\":"
                                    + bank.getBalance(id).toPlainString()
                                    + "}");
                } else {
                    List<LedgerEntry> list = bank.getHistory(id);

                    StringBuilder json = new StringBuilder("[");

                    for (int i = 0; i < list.size(); i++) {
                        if (i > 0) {
                            json.append(",");
                        }

                        json.append(list.get(i).toJson());
                    }

                    respond(ex, 200,
                            json.append("]").toString());
                }

            } catch (Exception e) {
                handleError(ex, e);
            }
        });

        // Serve index.html, style.css, and script.js
        server.createContext("/", ex -> {
            try {
                if (!ex.getRequestMethod().equals("GET")) {
                    respond(ex, 405,
                            "{\"error\":\"use GET\"}");
                    return;
                }

                String requested =
                        ex.getRequestURI().getPath();

                if (requested.equals("/")) {
                    requested = "/index.html";
                }

                Path frontend = Path.of("frontend")
                        .toAbsolutePath().normalize();

                Path file = frontend.resolve(
                        requested.substring(1)).normalize();

                if (!file.startsWith(frontend)
                        || !Files.isRegularFile(file)) {
                    respond(ex, 404,
                            "{\"error\":\"file not found\"}");
                    return;
                }

                String filename =
                        file.getFileName().toString();

                String contentType;

                if (filename.endsWith(".html")) {
                    contentType = "text/html; charset=UTF-8";
                } else if (filename.endsWith(".css")) {
                    contentType = "text/css; charset=UTF-8";
                } else if (filename.endsWith(".js")) {
                    contentType =
                            "application/javascript; charset=UTF-8";
                } else {
                    contentType =
                            "application/octet-stream";
                }

                byte[] bytes = Files.readAllBytes(file);

                ex.getResponseHeaders().set(
                        "Content-Type", contentType);

                ex.sendResponseHeaders(200, bytes.length);

                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }

            } catch (Exception e) {
                handleError(ex, e);
            }
        });

        server.start();

        System.out.println(
                "Banking Ledger running at http://localhost:" + port);
    }

    // Handle POST requests
    private static void post(
            HttpExchange ex,
            int successStatus,
            Action action) throws IOException {

        try {
            if (!ex.getRequestMethod().equals("POST")) {
                throw new BankException(405, "use POST");
            }

            String raw = new String(
                    ex.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);

            respond(ex, successStatus,
                    action.run(parseFlatJson(raw)));

        } catch (Exception e) {
            handleError(ex, e);
        }
    }

    // Error handling
    private static void handleError(
            HttpExchange ex, Exception e) throws IOException {

        if (e instanceof BankException) {
            BankException be = (BankException) e;

            respond(ex, be.getStatus(),
                    "{\"error\":\""
                            + escape(be.getMessage()) + "\"}");

        } else if (e instanceof SQLException) {
            e.printStackTrace();

            respond(ex, 500,
                    "{\"error\":\"database error\"}");

        } else {
            e.printStackTrace();

            respond(ex, 400,
                    "{\"error\":\""
                            + escape(e.getMessage() == null
                            ? "invalid request"
                            : e.getMessage())
                            + "\"}");
        }
    }

    // Send JSON response
    private static void respond(
            HttpExchange ex,
            int status,
            String json) throws IOException {

        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        ex.getResponseHeaders().set(
                "Content-Type", "application/json; charset=UTF-8");

        ex.sendResponseHeaders(status, bytes.length);

        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // Parse a flat JSON object
    private static Map<String, String> parseFlatJson(String json) {
        Map<String, String> map = new HashMap<>();

        Matcher matcher = Pattern.compile(
                "\"(\\w+)\"\\s*:\\s*(?:\"([^\"]*)\"|(-?[0-9.]+))")
                .matcher(json);

        while (matcher.find()) {
            map.put(matcher.group(1),
                    matcher.group(2) != null
                            ? matcher.group(2)
                            : matcher.group(3));
        }

        return map;
    }

    private static int intOf(
            Map<String, String> body,
            String key) throws BankException {

        try {
            return Integer.parseInt(body.get(key));
        } catch (NumberFormatException | NullPointerException e) {
            throw new BankException(
                    400, key + " must be a valid integer");
        }
    }

    private static BigDecimal amountOf(
            Map<String, String> body) throws BankException {

        try {
            return new BigDecimal(body.get("amount"));
        } catch (NumberFormatException | NullPointerException e) {
            throw new BankException(
                    400, "amount must be a valid number");
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
