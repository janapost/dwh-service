package kz.janapost.dwhservice.model.s2s;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Бизнес-событие из client-service ({@code event_s2s}, {@code eventName = s2s_event}). Каждый провайдер сам строит
 * из него свой запрос, поэтому здесь только исходные данные, без параметров конкретного провайдера.
 * Контракт — {@code context-files/dwh-service/UPM-2661-plan.md}, раздел «Контракт event_s2s».
 *
 * @param type              тип события
 * @param jpid              клиент
 * @param eventTime         время события в client-service, epoch ms (UTC). AppsFlyer получает его как {@code eventTime}
 * @param orderId           заказ: ORDER_CREATED, FIRST_ORDER, RECEIVED_THE_GOODS
 * @param paymentId         ID оплаты JanaPay (PURCHASE): ключ дедупликации; {@code null} — ключ из ID заказов
 * @param orders            заказы оплаты (PURCHASE)
 * @param localAmount       стоимость доставки в местной валюте ×10000 ({@code RoadPrice.value}): ORDER_CREATED
 * @param usdValue          стоимость доставки в USD ×10000 ({@code RoadPrice.usdValue}): FIRST_ORDER; {@code null} — нет цены
 * @param marketplace       маркетплейс заказа ({@code SyncOrderInfo.creator}), может быть {@code null}
 * @param deliveryDaysTotal целые дни от создания заказа до выдачи: RECEIVED_THE_GOODS; {@code null} — время неизвестно
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record S2SEvent(
        S2SEventType type,
        String jpid,
        Long eventTime,
        String orderId,
        String paymentId,
        List<S2SOrder> orders,
        Long localAmount,
        Long usdValue,
        String marketplace,
        Long deliveryDaysTotal
) {
}
