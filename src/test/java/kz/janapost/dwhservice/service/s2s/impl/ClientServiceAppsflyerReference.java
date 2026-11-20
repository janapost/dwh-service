package kz.janapost.dwhservice.service.s2s.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Эталон: <b>дословная копия</b> сборки параметров AppsFlyer из client-service (main на 19.11.2026). Тест сравнивает с ней
 * то, что строит dwh-service, — так проверяется совместимость «байт в байт», а не повтор реализации dwh.
 * Не менять вместе с dwh: это фиксированное поведение client-service.
 */
final class ClientServiceAppsflyerReference {

    private ClientServiceAppsflyerReference() {
    }

    /** {@code AppsflyerS2SServiceImpl.REGISTRATION_PARAMS}. */
    static Map<String, Object> registration() {
        return Map.of("af_registration_method", "phone");
    }

    /** {@code SyncOrderProcess.sendAppsflyerOrderCreatedEvent}. */
    static Map<String, Object> orderCreated(String orderId, long roadPriceValue, String creator) {
        Map<String, Object> params = new HashMap<>();
        params.put("order_id", orderId);
        params.put("total_amount", roadPriceValue);
        params.put("marketplace", creator);
        return params;
    }

    /** {@code SyncOrderProcess.sendAppsflyerFirstOrderEvent}; {@code usdValue == null} — у заказа нет RoadPrice. */
    static Map<String, Object> firstOrder(String orderId, Long usdValue, String creator) {
        Map<String, Object> params = new HashMap<>();
        params.put("order_id", orderId);
        double revenueUsd = usdValue != null
                ? usdValue / 10000.0
                : 0.0;
        if (revenueUsd > 0) {
            params.put("af_revenue", revenueUsd);
            params.put("af_currency", "USD");
        }
        if (creator != null) {
            params.put("marketplace", creator);
        }
        return params;
    }

    /** {@code OrderPaymentService_27_10_2024.buildAfPurchaseParams}: заказ — {@code [id, usdValue или null]}. */
    static Map<String, Object> purchase(List<Object[]> orders) {
        Map<String, Object> params = new HashMap<>();
        long revenueUsd = orders.stream()
                .filter(o -> o[1] != null)
                .mapToLong(o -> (Long) o[1])
                .sum();
        params.put("af_revenue", revenueUsd / 10000.0);
        params.put("af_currency", "USD");
        params.put("af_quantity", orders.size());
        params.put("af_content_id", orders.stream().map(o -> (String) o[0]).collect(Collectors.joining(",")));
        return params;
    }

    /** {@code DeliveredEventSubOrderStatusProcessor.sendAppsflyerEvent}; {@code deliveryDays == null} — нет времени. */
    static Map<String, Object> received(String orderId, Long deliveryDays) {
        Map<String, Object> params = new HashMap<>();
        params.put("order_id", orderId);
        if (deliveryDays != null) {
            params.put("delivery_days_total", deliveryDays);
        }
        return params;
    }
}
