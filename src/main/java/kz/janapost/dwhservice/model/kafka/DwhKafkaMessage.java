package kz.janapost.dwhservice.model.kafka;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Сообщение в {@code event_dwh_log} / {@code event_dwh_stat}. Формат совпадает с конвертом jpevent
 * ({@code kz.janapost.jpevent.event.EventData}): бэкенд-сервисы шлют его через {@code EventService.doEvent},
 * ClickHouse разбирает оба источника одним materialized view.
 */
@Data
public class DwhKafkaMessage {
    private String topic;
    private Sender sender;
    private String eventName;
    private Map<String, Object> data;

    @Data
    public static class Sender {
        /** Имя источника: {@code spring.application.name} у бэкенда, {@code mobile-android|ios} у приложения. */
        private String appName;
        private String jpid;
        /** Время приёма сервером, epoch ms. */
        private long timestamp;
        private Map<String, Object> params = new LinkedHashMap<>();
    }
}
