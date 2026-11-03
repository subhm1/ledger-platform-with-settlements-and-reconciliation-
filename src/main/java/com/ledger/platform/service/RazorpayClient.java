package com.ledger.platform.service;

import java.util.List;
import java.util.Map;

public interface RazorpayClient {
    Map<String, Object> createOrder(String receipt, long amountMinor, String currency, String reference);

    Map<String, Object> fetchPayment(String paymentId);

    Map<String, Object> fetchOrder(String orderId);

    List<Map<String, Object>> findOrdersByReceipt(String receipt);
}
