# Banking Ledger API

REST backend in **Java + JDBC + MySQL**. Users register, deposit money, and transfer
between accounts. Every money movement is an atomic database transaction, and each
one is recorded in a ledger table.

## Features
- Register / login (passwords hashed with salted PBKDF2, never stored in plain text)
- Deposit and account-to-account transfer (JDBC transactions: commit / rollback)
- Row locking (`SELECT ... FOR UPDATE`, ascending id order) so concurrent transfers
  cannot overdraw an account or deadlock
- `BigDecimal` for money, `PreparedStatement` everywhere (no SQL injection)
- Balance lookup and transaction history
- Uses the JDK's built-in HTTP server, so no framework is needed

## Setup
1. Install JDK 17+ and MySQL 8.
2. Create the tables: `mysql -u root -p < sql/schema.sql`
3. Download the MySQL driver jar (`mysql-connector-j-8.x.x.jar`) from
   https://dev.mysql.com/downloads/connector/j/ (choose "Platform Independent")
   and put it in a `lib/` folder.
4. Compile:
   - Linux/Mac: `mkdir out && javac -d out src/com/bankledger/*.java`
   - Windows: `mkdir out` then `javac -d out src\com\bankledger\*.java`
5. Set your database login (defaults: user `root`, empty password):
   - Linux/Mac: `export DB_USER=root DB_PASSWORD=yourpassword`
   - Windows: `set DB_USER=root` and `set DB_PASSWORD=yourpassword`
6. Run:
   - Linux/Mac: `java -cp out:lib/mysql-connector-j-8.x.x.jar com.bankledger.ApiServer`
   - Windows: `java -cp "out;lib\mysql-connector-j-8.x.x.jar" com.bankledger.ApiServer`

Server starts on http://localhost:8080

## Endpoints (test in Postman, body type: raw JSON)
| Method | URL | Body |
|---|---|---|
| POST | /register | `{"name":"Asha","email":"asha@test.com","password":"secret123"}` |
| POST | /login | `{"email":"asha@test.com","password":"secret123"}` |
| POST | /deposit | `{"accountId":1,"amount":100.00}` |
| POST | /transfer | `{"fromAccount":1,"toAccount":2,"amount":40.00}` |
| GET | /accounts/1/balance | |
| GET | /accounts/1/transactions | |

Status codes: 200/201 success, 400 bad input, 401 wrong login, 404 account not found,
409 duplicate email, 422 insufficient balance.

## Project structure
- `BankService` - business logic and transactions
- `ApiServer` - HTTP routes and JSON
- `DBConnection` - JDBC connection settings
- `PasswordUtil` - password hashing
- `LedgerEntry` - model class for a transaction row
- `BankException` - business errors with HTTP status

## Possible improvements
Spring Boot, JWT authentication, pagination, unit tests, idempotency keys.
