package kz.janapost.dwhservice.service.s2s.impl;

import kz.janapost.dwhservice.model.appsflyer.AppsflyerEventName;
import kz.janapost.dwhservice.model.s2s.S2SEvent;
import kz.janapost.dwhservice.model.s2s.S2SOrder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * {@link S2SEvent} → событие AppsFlyer. Имена, параметры и ключи дедупликации — <b>ровно те, что строили триггеры
 * client-service</b>; порядок вставки в {@link HashMap} тот же, поэтому совпадает и строка {@code eventValue}.
 * <table>
 *     <caption>Маппинг</caption>
 *     <tr><th>Тип</th><th>Событие</th><th>Параметры</th><th>businessId</th></tr>
 *     <tr><td>COMPLETE_REGISTRATION</td><td>af_complete_registration</td><td>af_registration_method=phone</td><td>jpid</td></tr>
 *     <tr><td>ORDER_CREATED</td><td>order_created</td><td>order_id, total_amount, marketplace</td><td>orderId</td></tr>
 *     <tr><td>FIRST_ORDER</td><td>first_order</td><td>order_id, af_revenue + af_currency (если &gt; 0), marketplace (если есть)</td><td>jpid</td></tr>
 *     <tr><td>PURCHASE</td><td>af_purchase</td><td>af_revenue, af_currency, af_quantity, af_content_id</td><td>ID оплаты или ID заказов</td></tr>
 *     <tr><td>RECEIVED_THE_GOODS</td><td>received_the_goods</td><td>order_id, delivery_days_total (если известно)</td><td>orderId</td></tr>
 * </table>
 */
final class AppsflyerEventMapping {
    static final String USD = "USD";
    private static final double MONEY_SCALE = 10000.0;

    private AppsflyerEventMapping() {
    }

    static AppsflyerEventName eventName(S2SEvent event) {
        return switch (event.type()) {
            case COMPLETE_REGISTRATION -> AppsflyerEventName.AF_COMPLETE_REGISTRATION;
            case ORDER_CREATED -> AppsflyerEventName.ORDER_CREATED;
            case FIRST_ORDER -> AppsflyerEventName.FIRST_ORDER;
            case PURCHASE -> AppsflyerEventName.AF_PURCHASE;
            case RECEIVED_THE_GOODS -> AppsflyerEventName.RECEIVED_THE_GOODS;
        };
    }

    /** {@code AppsflyerEventPO.orderId}: у регистрации и оплаты заказа нет. */
    static String orderId(S2SEvent event) {
        return switch (event.type()) {
            case ORDER_CREATED, FIRST_ORDER, RECEIVED_THE_GOODS -> event.orderId();
            case COMPLETE_REGISTRATION, PURCHASE -> null;
        };
    }

    /** Вторая часть {@code dedupKey} ({@code <eventName>:<businessId>}). */
    static String businessId(S2SEvent event) {
        return switch (event.type()) {
            case COMPLETE_REGISTRATION, FIRST_ORDER -> event.jpid();
            case ORDER_CREATED, RECEIVED_THE_GOODS -> event.orderId();
            // повтор колбэка той же оплаты даёт тот же ключ; без ID транзакции — ID заказов по возрастанию
            case PURCHASE -> event.paymentId() != null ? event.paymentId() : sortedOrderIds(event.orders());
        };
    }

    static Map<String, Object> params(S2SEvent event) {
        return switch (event.type()) {
            case COMPLETE_REGISTRATION -> registrationParams();
            case ORDER_CREATED -> orderCreatedParams(event);
            case FIRST_ORDER -> firstOrderParams(event);
            case PURCHASE -> purchaseParams(orders(event));
            case RECEIVED_THE_GOODS -> receivedParams(event);
        };
    }

    /** ТЗ AppsFlyer v4, п. 2: только способ регистрации, номер телефона не передаётся. */
    private static Map<String, Object> registrationParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("af_registration_method", "phone");
        return params;
    }

    /** {@code SyncOrderProcess.sendAppsflyerOrderCreatedEvent}: total_amount — RoadPrice.value (местная валюта ×10000). */
    private static Map<String, Object> orderCreatedParams(S2SEvent event) {
        Map<String, Object> params = new HashMap<>();
        params.put("order_id", event.orderId());
        params.put("total_amount", event.localAmount());
        params.put("marketplace", event.marketplace());
        return params;
    }

    /** {@code SyncOrderProcess.sendAppsflyerFirstOrderEvent}. */
    private static Map<String, Object> firstOrderParams(S2SEvent event) {
        Map<String, Object> params = new HashMap<>();
        params.put("order_id", event.orderId());
        double revenueUsd = event.usdValue() != null ? event.usdValue() / MONEY_SCALE : 0.0;
        if (revenueUsd > 0) {
            params.put("af_revenue", revenueUsd);
            params.put("af_currency", USD);
        }
        if (event.marketplace() != null) {
            params.put("marketplace", event.marketplace());
        }
        return params;
    }

    /**
     * {@code OrderPaymentService_27_10_2024.buildAfPurchaseParams}: af_content_id — ID заказов через запятую,
     * af_quantity — число заказов, af_revenue — сумма доставки в USD.
     */
    private static Map<String, Object> purchaseParams(List<S2SOrder> orders) {
        Map<String, Object> params = new HashMap<>();
        long revenueUsd = orders.stream()
                .map(S2SOrder::usdValue)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();
        params.put("af_revenue", revenueUsd / MONEY_SCALE);
        params.put("af_currency", USD);
        params.put("af_quantity", orders.size());
        params.put("af_content_id", orders.stream().map(S2SOrder::id).collect(Collectors.joining(",")));
        return params;
    }

    /** {@code DeliveredEventSubOrderStatusProcessor.sendAppsflyerEvent}. */
    private static Map<String, Object> receivedParams(S2SEvent event) {
        Map<String, Object> params = new HashMap<>();
        params.put("order_id", event.orderId());
        if (event.deliveryDaysTotal() != null) {
            params.put("delivery_days_total", event.deliveryDaysTotal());
        }
        return params;
    }

    private static List<S2SOrder> orders(S2SEvent event) {
        return event.orders() == null ? List.of() : event.orders();
    }

    private static String sortedOrderIds(List<S2SOrder> orders) {
        return orders == null ? "" : orders.stream().map(S2SOrder::id).sorted().collect(Collectors.joining(","));
    }
}
