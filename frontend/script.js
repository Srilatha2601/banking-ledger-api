
const API = "";

async function request(path, options = {}) {
    const response = await fetch(API + path, {
        ...options,
        headers: {
            "Content-Type": "application/json",
            ...options.headers
        }
    });

    const text = await response.text();
    let data = {};

    try {
        data = text ? JSON.parse(text) : {};
    } catch {
        throw new Error("Invalid response from the Java server.");
    }

    if (!response.ok) {
        throw new Error(data.error || data.message || "Request failed.");
    }

    return data;
}

function accountId() {
    const id = Number(document.getElementById("accountId").value);

    if (!Number.isInteger(id) || id <= 0) {
        throw new Error("Enter a valid account ID.");
    }

    return id;
}

function amountOf(elementId) {
    const amount = Number(document.getElementById(elementId).value);

    if (!Number.isFinite(amount) || amount <= 0) {
        throw new Error("Enter an amount greater than zero.");
    }

    return amount;
}

function showMessage(message) {
    document.getElementById("message").textContent = message;
}

async function loadAccount() {
    try {
        const id = accountId();
        const data = await request(`/accounts/${id}/balance`);

        document.getElementById("balance").textContent =
            "₹" + Number(data.balance).toFixed(2);

        showMessage("Account loaded successfully.");
    } catch (error) {
        showMessage(error.message);
    }
}

async function depositMoney() {
    try {
        const id = accountId();
        const amount = amountOf("depositAmount");

        const data = await request("/deposit", {
            method: "POST",
            body: JSON.stringify({
                accountId: id,
                amount: amount
            })
        });

        showMessage(data.message || "Deposit successful.");
        await loadAccount();
        await loadHistory();
    } catch (error) {
        showMessage(error.message);
    }
}

async function withdrawMoney() {
    try {
        const id = accountId();
        const amount = amountOf("withdrawAmount");

        const data = await request("/withdraw", {
            method: "POST",
            body: JSON.stringify({
                accountId: id,
                amount: amount
            })
        });

        showMessage(data.message || "Withdrawal successful.");
        await loadAccount();
        await loadHistory();
    } catch (error) {
        showMessage(error.message);
    }
}

async function transferMoney() {
    try {
        const id = accountId();
        const toAccount = Number(
            document.getElementById("toAccount").value
        );
        const amount = amountOf("transferAmount");

        if (!Number.isInteger(toAccount) || toAccount <= 0) {
            throw new Error("Enter a valid receiver account ID.");
        }

        if (toAccount === id) {
            throw new Error("Choose a different receiver account.");
        }

        const data = await request("/transfer", {
            method: "POST",
            body: JSON.stringify({
                fromAccount: id,
                toAccount: toAccount,
                amount: amount
            })
        });

        showMessage(data.message || "Transfer successful.");
        await loadAccount();
        await loadHistory();
    } catch (error) {
        showMessage(error.message);
    }
}

async function loadHistory() {
    try {
        const id = accountId();

        const data = await request(
            `/accounts/${id}/transactions`
        );

        if (!Array.isArray(data)) {
            throw new Error("Unexpected transaction history response.");
        }

        const tbody = document.getElementById("historyBody");
        tbody.replaceChildren();

        data.forEach(transaction => {
            const row = document.createElement("tr");

            const values = [
                transaction.id,
                transaction.type,
                transaction.amount,
                transaction.date ?? transaction.createdAt
                    ?? transaction.created_at
            ];

            values.forEach(value => {
                const cell = document.createElement("td");
                cell.textContent = value ?? "-";
                row.appendChild(cell);
            });

            tbody.appendChild(row);
        });

        showMessage("Transaction history loaded.");
    } catch (error) {
        showMessage(error.message);
    }
}
