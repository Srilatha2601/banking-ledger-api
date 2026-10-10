package com.bankledger;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * All banking logic. Every money-moving method runs inside ONE database
 * transaction (setAutoCommit(false) -> commit / rollback), so it is atomic.
 */
public class BankService {

    // ---------- registration & login ----------

    /** Creates a user and their account together. Returns the new account id. */
    public int register(String name, String email, String password) throws BankException, SQLException {
        if (isBlank(name) || isBlank(email) || isBlank(password)) {
            throw new BankException(400, "name, email and password are required");
        }
        if (password.length() < 6) {
            throw new BankException(400, "password must be at least 6 characters");
        }
        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int userId;
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO users (name, email, password_hash) VALUES (?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, name.trim());
                    ps.setString(2, email.trim().toLowerCase());
                    ps.setString(3, PasswordUtil.hash(password));
                    ps.executeUpdate();
                    userId = firstKey(ps);
                }
                int accountId;
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO accounts (user_id, balance) VALUES (?, 0)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setInt(1, userId);
                    ps.executeUpdate();
                    accountId = firstKey(ps);
                }
                conn.commit();
                return accountId;
            } catch (SQLException e) {
                conn.rollback();
                if (e.getErrorCode() == 1062) { // duplicate entry
                    throw new BankException(409, "email already registered");
                }
                throw e;
            }
        }
    }

    /** Returns the account id if email + password are correct. */
    public int login(String email, String password) throws BankException, SQLException {
        String sql = "SELECT u.password_hash, a.id FROM users u "
                   + "JOIN accounts a ON a.user_id = u.id WHERE u.email = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email == null ? "" : email.trim().toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && password != null && PasswordUtil.verify(password, rs.getString(1))) {
                    return rs.getInt(2);
                }
            }
        }
        throw new BankException(401, "invalid email or password");
    }

    // ---------- money operations ----------

    public BigDecimal deposit(int accountId, BigDecimal amount) throws BankException, SQLException {
        validateAmount(amount);
        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                BigDecimal balance = lockAndGetBalance(conn, accountId);
                BigDecimal newBalance = balance.add(amount);
                updateBalance(conn, accountId, newBalance);
                insertLedger(conn, null, accountId, amount, "DEPOSIT");
                conn.commit();
                return newBalance;
            } catch (BankException | SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /**
     * Atomic transfer: debit + credit + ledger row all commit together,
     * or all roll back. Rows are locked with SELECT ... FOR UPDATE, always in
     * ascending id order, so concurrent transfers cannot overdraw or deadlock.
     */
    public void transfer(int fromId, int toId, BigDecimal amount) throws BankException, SQLException {
        validateAmount(amount);
        if (fromId == toId) {
            throw new BankException(400, "cannot transfer to the same account");
        }
        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Map<Integer, BigDecimal> balances = new HashMap<>();
                int first = Math.min(fromId, toId);
                int second = Math.max(fromId, toId);
                balances.put(first, lockAndGetBalance(conn, first));
                balances.put(second, lockAndGetBalance(conn, second));

                if (balances.get(fromId).compareTo(amount) < 0) {
                    throw new BankException(422, "insufficient balance");
                }
                updateBalance(conn, fromId, balances.get(fromId).subtract(amount));
                updateBalance(conn, toId, balances.get(toId).add(amount));
                insertLedger(conn, fromId, toId, amount, "TRANSFER");
                conn.commit();
            } catch (BankException | SQLException e) {
                conn.rollback(); // nothing is half-applied
                throw e;
            }
        }
    }

    // ---------- read operations ----------

    public BigDecimal getBalance(int accountId) throws BankException, SQLException {
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT balance FROM accounts WHERE id = ?")) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getBigDecimal(1);
                }
            }
        }
        throw new BankException(404, "account not found");
    }

    public List<LedgerEntry> getHistory(int accountId) throws BankException, SQLException {
        getBalance(accountId); // throws 404 if the account does not exist
        List<LedgerEntry> history = new ArrayList<>();
        String sql = "SELECT id, from_account, to_account, amount, type, created_at "
                   + "FROM transactions WHERE from_account = ? OR to_account = ? ORDER BY id DESC";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            ps.setInt(2, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    history.add(new LedgerEntry(
                            rs.getLong("id"),
                            (Integer) rs.getObject("from_account"),
                            (Integer) rs.getObject("to_account"),
                            rs.getBigDecimal("amount"),
                            rs.getString("type"),
                            String.valueOf(rs.getTimestamp("created_at"))));
                }
            }
        }
        return history;
    }

    // ---------- helpers ----------

    /** Locks the account row until commit/rollback and returns its balance. */
    private BigDecimal lockAndGetBalance(Connection conn, int accountId) throws BankException, SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT balance FROM accounts WHERE id = ? FOR UPDATE")) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getBigDecimal(1);
                }
            }
        }
        throw new BankException(404, "account " + accountId + " not found");
    }

    private void updateBalance(Connection conn, int accountId, BigDecimal newBalance) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE accounts SET balance = ? WHERE id = ?")) {
            ps.setBigDecimal(1, newBalance);
            ps.setInt(2, accountId);
            ps.executeUpdate();
        }
    }

    private void insertLedger(Connection conn, Integer from, Integer to, BigDecimal amount, String type)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO transactions (from_account, to_account, amount, type) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, from);
            ps.setObject(2, to);
            ps.setBigDecimal(3, amount);
            ps.setString(4, type);
            ps.executeUpdate();
        }
    }

    private void validateAmount(BigDecimal amount) throws BankException {
        if (amount == null || amount.signum() <= 0) {
            throw new BankException(400, "amount must be greater than 0");
        }
        if (amount.scale() > 2) {
            throw new BankException(400, "amount can have at most 2 decimal places");
        }
    }

    private int firstKey(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            keys.next();
            return keys.getInt(1);
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
