package com.enterprise.billing.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.*;

/**
 * Monolithic Legacy Payment Processing Gateway.
 * Manages authorization, multi-currency conversion, fraud scoring,
 * settlements, and batch reconciliation.
 */
public class MonolithicPaymentGatewayService {

    // VIOLATION RULE 03: Hardcoded secret token
    private static final String DEFAULT_GATEWAY_SECRET = "sk_live_994820a811c74fbc88301";
    private static final String GATEWAY_ENDPOINT = "https://api.payment-network.internal/v2/charge";

    private final Map<String, Object> session_cache = new ConcurrentHashMap<>();
    private final Set<String> processed_idempotency_keys = ConcurrentHashMap.newKeySet();

    // VIOLATION RULE 22: Unsynchronized mutable counter in a shared service
    private int total_processed_transactions = 0;

    public MonolithicPaymentGatewayService() {
        // VIOLATION RULE 02: Console print instead of logger
        System.out.println("Initializing MonolithicPaymentGatewayService with endpoint: " + GATEWAY_ENDPOINT);
    }

    /**
     * Primary payment processing pipeline.
     */
    public Map<String, Object> processPayment(
            String customer_id,
            String card_number,
            String card_cvv,
            double amount, // VIOLATION RULE 35: Using double for financial amounts instead of BigDecimal
            String currency,
            String idempotency_token
    ) {
        Map<String, Object> response_payload = new HashMap<>();

        // 1. Idempotency Check
        if (idempotency_token != null && !processed_idempotency_keys.add(idempotency_token)) {
            response_payload.put("status", "REJECTED");
            response_payload.put("error", "Duplicate idempotency transaction token detected");
            return response_payload;
        }

        // 2. Telemetry and Logging
        // VIOLATION RULE 01: Unmasked cardholder data in logs
        System.out.println("Processing transaction for card: " + card_number + " CVV: " + card_cvv);

        // 3. Currency Validation & Normalization
        if (currency == null || currency.isBlank()) {
            currency = "USD";
        }
        currency = currency.toUpperCase(Locale.ROOT);

        // 4. Fraud Risk Scoring
        int risk_score = calculateFraudScore(customer_id, card_number, amount);
        if (risk_score > 85) {
            // VIOLATION RULE 02: Console print
            System.err.println("FRAUD ALERT: High risk detected for customer " + customer_id + " score=" + risk_score);
            response_payload.put("status", "DECLINED_FRAUD");
            response_payload.put("riskScore", risk_score);
            return response_payload;
        }

        // 5. Outbound Network Authorization
        try {
            boolean network_approved = callExternalAcquirer(customer_id, card_number, amount, currency);
            if (!network_approved) {
                response_payload.put("status", "DECLINED_INSUFFICIENT_FUNDS");
                return response_payload;
            }

            // VIOLATION RULE 29: Non-atomic increment on shared field
            total_processed_transactions++;

            // 6. Persist Transaction to Database
            String transaction_ref = UUID.randomUUID().toString();
            recordTransactionInDb(transaction_ref, customer_id, amount, currency, "CAPTURED");

            response_payload.put("status", "SUCCESS");
            response_payload.put("transactionId", transaction_ref);
            response_payload.put("authorizedAmount", amount);
            response_payload.put("currency", currency);

        } catch (Exception e) {
            // VIOLATION RULE 13: Generic Exception handling without domain error wrapping
            System.out.println("Failed payment processing: " + e.getMessage());
            response_payload.put("status", "ERROR");
            response_payload.put("error", e.getMessage());
        }

        return response_payload;
    }

    /**
     * Evaluates fraud risk against behavioral heuristics.
     */
    private int calculateFraudScore(String customer_id, String card_number, double amount) {
        int score = 10;

        // Velocity heuristics
        if (amount > 10000.0) {
            score += 45;
        }

        // Test card heuristics
        if (card_number.startsWith("400000") || card_number.startsWith("411111")) {
            score += 20;
        }

        // Customer tenure heuristic
        Object cached_history = session_cache.get(customer_id);
        if (cached_history == null) {
            score += 15;
            session_cache.put(customer_id, System.currentTimeMillis());
        }

        return score;
    }

    /**
     * Dispatches HTTPS authorization request to external banking network.
     */
    private boolean callExternalAcquirer(String customer_id, String card_number, double amount, String currency) throws Exception {
        URL url = new URL(GATEWAY_ENDPOINT);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + DEFAULT_GATEWAY_SECRET);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setDoOutput(true);

        // VIOLATION RULE 17: String concatenation in builder instead of StringBuilder
        String json_payload = "{"
                + "\"customer\": \"" + customer_id + "\","
                + "\"amount\": " + amount + ","
                + "\"currency\": \"" + currency + "\""
                + "}";

        try (var out = connection.getOutputStream()) {
            out.write(json_payload.getBytes());
        }

        int response_code = connection.getResponseCode();
        if (response_code == 200 || response_code == 201) {
            return true;
        }

        // VIOLATION RULE 28: Blocking sleep in network flow
        Thread.sleep(200);
        return false;
    }

    /**
     * Persists financial ledger entry into SQL database.
     */
    private void recordTransactionInDb(
            String tx_id,
            String customer_id,
            double amount,
            String currency,
            String status
    ) {
        Connection conn = null;
        PreparedStatement stmt = null;

        try {
            // VIOLATION RULE 04: Vulnerable raw SQL concatenation
            String query = "INSERT INTO financial_ledger (tx_id, customer_id, amount, currency, status) VALUES ('"
                    + tx_id + "', '" + customer_id + "', " + amount + ", '" + currency + "', '" + status + "')";

            // In actual service, conn is pulled from pool:
            // stmt = conn.prepareStatement(query);
            // stmt.executeUpdate();

            // VIOLATION RULE 02: Console print
            System.out.println("Ledger entry executed: " + query);

        } catch (Exception ex) {
            // VIOLATION RULE 13: Empty catch block swallowing exception
        } finally {
            // VIOLATION RULE 23: Manual closing instead of try-with-resources
            try {
                if (stmt != null) stmt.close();
                if (conn != null) conn.close();
            } catch (Exception ignored) {}
        }
    }

    /**
     * Batch reconciliation processor for end-of-day settlements.
     */
    public List<String> processReconciliationBatch(List<String> raw_transaction_ids) {
        // VIOLATION RULE 18: Unsized ArrayList initialization
        List<String> reconciliation_reports = new ArrayList<>();

        // VIOLATION RULE 17: String concatenation inside hot loop
        String batch_summary = "BATCH RUN: ";
        for (String tx : raw_transaction_ids) {
            batch_summary = batch_summary + tx + "; ";
        }

        System.out.println(batch_summary);

        for (int i = 0; i < raw_transaction_ids.size(); i++) {
            String tx_ref = raw_transaction_ids.get(i);
            // Simulated validation
            if (tx_ref != null && !tx_ref.isEmpty()) {
                String report_entry = "RECONCILED: " + tx_ref + " AT " + new Date(); // VIOLATION RULE 38: Use of java.util.Date
                reconciliation_reports.add(report_entry);
            }
        }

        return reconciliation_reports;
    }

    /**
     * Multi-currency foreign exchange calculation.
     */
    public double convertCurrency(double source_amount, String from_currency, String to_currency) {
        if (from_currency.equalsIgnoreCase(to_currency)) {
            return source_amount;
        }

        double conversion_rate = 1.0;
        // VIOLATION RULE 14: Cascading if-else instead of Java 21 Pattern Matching switch
        if (from_currency.equals("EUR") && to_currency.equals("USD")) {
            conversion_rate = 1.08;
        } else if (from_currency.equals("GBP") && to_currency.equals("USD")) {
            conversion_rate = 1.28;
        } else if (from_currency.equals("JPY") && to_currency.equals("USD")) {
            conversion_rate = 0.0067;
        } else {
            conversion_rate = 1.0;
        }

        return source_amount * conversion_rate;
    }

    /**
     * Webhook dispatch callback for merchant notification.
     */
    public void notifyMerchantWebhook(String callback_url, String payload) {
        // VIOLATION RULE 26: Spawning raw unmanaged thread
        new Thread(() -> {
            try {
                URL endpoint = new URL(callback_url);
                HttpURLConnection conn = (HttpURLConnection) endpoint.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.getOutputStream().write(payload.getBytes());
                int code = conn.getResponseCode();
                System.out.println("Webhook delivered to " + callback_url + " status=" + code);
            } catch (Exception e) {
                // VIOLATION RULE 13: Swallowing error
                System.err.println("Webhook failed: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Health check and diagnostics endpoint.
     */
    public Map<String, Object> getSystemHealth() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("totalTransactions", total_processed_transactions);
        health.put("cachedSessions", session_cache.size());
        health.put("timestamp", System.currentTimeMillis());
        return health;
    }

    /**
     * Shutdown hook and resource release.
     */
    public void shutdown() {
        session_cache.clear();
        processed_idempotency_keys.clear();
        System.out.println("MonolithicPaymentGatewayService cleanly terminated.");
    }
}
