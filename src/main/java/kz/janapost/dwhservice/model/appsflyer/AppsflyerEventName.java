package kz.janapost.dwhservice.model.appsflyer;

/**
 * Имена событий AppsFlyer. Перенесено из client-service без изменений: значения хранятся в журнале
 * ({@code AppsflyerEventPO.eventName}) и уходят в AppsFlyer как {@code eventName}.
 */
public enum AppsflyerEventName {
    LOGIN("login"),
    REGISTRATION("registration"),
    PAID_FOR_DELIVERY("paid_for_delivery"),
    RECEIVED_THE_GOODS("received_the_goods"),
    OPENED_THE_LESSON("opened_the_lesson"),
    LESSON_PLATFORM_OPENED("lesson_platform_opened"),
    COPIED_ADDRESS("copied_address"),
    ORDER_CREATED("order_created"),
    AF_PURCHASE("af_purchase"),
    FIRST_ORDER("first_order"),
    AF_COMPLETE_REGISTRATION("af_complete_registration");

    private final String value;

    AppsflyerEventName(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
