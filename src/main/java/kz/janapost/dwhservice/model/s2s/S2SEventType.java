package kz.janapost.dwhservice.model.s2s;

/** Бизнес-события, которые client-service передаёт S2S-провайдерам. Без привязки к провайдеру. */
public enum S2SEventType {
    /** Создан аккаунт (новый номер прошёл SMS). businessId = jpid. */
    COMPLETE_REGISTRATION,
    /** Создан заказ. businessId = orderId. */
    ORDER_CREATED,
    /** Первый заказ клиента. businessId = jpid. */
    FIRST_ORDER,
    /** Оплата доставки (одна оплата — одно событие на все её заказы). businessId = ID оплаты. */
    PURCHASE,
    /** Посылка выдана клиенту. businessId = orderId. */
    RECEIVED_THE_GOODS
}
