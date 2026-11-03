package com.ledger.platform.service;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public final class RazorpayGateway implements RazorpayClient {
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE = new ParameterizedTypeReference<>() { };
    private final RazorpayConfiguration configuration;
    private final RestClient client;

    public RazorpayGateway(RazorpayConfiguration configuration) {
        this.configuration = configuration;
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder().baseUrl("https://api.razorpay.com")
                .requestFactory(factory).build();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> createOrder(String receipt, long amountMinor, String currency, String reference) {
        Map<String, Object> body = Map.of(
                "amount", amountMinor,
                "currency", currency,
                "receipt", receipt,
                "notes", Map.of("business_reference", reference));
        return authenticated().post().uri("/v1/orders").body(body).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> fetchPayment(String paymentId) {
        return authenticated().get().uri("/v1/payments/{paymentId}", paymentId).retrieve().body(MAP_TYPE);
    }

    public Map<String, Object> fetchOrder(String orderId) {
        return authenticated().get().uri("/v1/orders/{orderId}", orderId).retrieve().body(MAP_TYPE);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> findOrdersByReceipt(String receipt) {
        Map<String, Object> response = authenticated().get()
                .uri(uri -> uri.path("/v1/orders").queryParam("receipt", receipt).queryParam("count", 100).build())
                .retrieve().body(MAP_TYPE);
        Object items = response == null ? null : response.get("items");
        if (!(items instanceof List<?> list)) return List.of();
        return (List<Map<String, Object>>) list;
    }

    private RestClient authenticated() {
        if (!configuration.configured()) {
            throw new ProviderPaymentException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "PROVIDER_NOT_CONFIGURED", "Razorpay Test Mode is not configured.");
        }
        return client.mutate().defaultHeaders(headers -> headers.setBasicAuth(
                configuration.keyId(), configuration.apiSecret())).build();
    }
}


