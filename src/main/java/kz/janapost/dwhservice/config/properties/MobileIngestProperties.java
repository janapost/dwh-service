package kz.janapost.dwhservice.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Эндпоинты приёма логов и статистики мобильного приложения:
 * {@code /client/v1/log|stat/add-batch} (после входа) и {@code /public/v1/log/add-batch} (до входа).
 * Данные публикуются в Kafka, дальше их читает ClickHouse (Kafka engine).
 */
@Data
@Component
@ConfigurationProperties(prefix = "kz.janapost.dwh-service.mobile")
public class MobileIngestProperties {
    /** Выключено — эндпоинты после входа отвечают ok и ничего не публикуют. */
    private boolean enabled;
    /** Выключено — {@code /public/**} отвечает ok и ничего не публикует. */
    private boolean publicEnabled;

    private String logTopic = "event_dwh_log";
    private String statTopic = "event_dwh_stat";

    private int maxItems = 100;
    private int publicMaxItems = 20;
    private long maxBodyBytes = 524_288;
    private long publicMaxBodyBytes = 65_536;

    /**
     * Общий дедлайн пачки: передача записей продюсеру + подтверждение Kafka.
     * Должен быть больше {@code kafka.delivery-timeout-ms}: иначе приложение получит {@code -3} и повторит пачку,
     * а Kafka доставит первую попытку позже — получатся дубли. Проверяется при старте.
     */
    private long kafkaSendTimeoutMs = 6000;

    /**
     * Время записи с устройства принимается, только если оно не старше этого числа дней и не впереди
     * больше чем на {@link #maxClientTimestampAheadMs}. Иначе — время сервера, а исходное значение уходит
     * в {@code attributes.clientTimestamp} / {@code tags.clientTimestamp}. Защищает ClickHouse от пачек
     * с сотнями разных месяцев ({@code max_partitions_per_insert_block}) и от записей, сразу старше TTL.
     */
    private int maxClientTimestampAgeDays = 30;
    private long maxClientTimestampAheadMs = 86_400_000;

    private Kafka kafka = new Kafka();

    private RateLimit rateLimit = new RateLimit();

    /**
     * Свой продюсер эндпоинтов приложения. Общие {@code spring.kafka.producer.*} не трогаем: через них пишет
     * библиотека {@code monitoring}, ей эти жёсткие таймауты не нужны.
     */
    @Data
    public static class Kafka {
        private String acks = "all";
        private boolean idempotence = true;
        /** Сколько {@code send()} может блокировать поток Tomcat: нет метаданных или полон буфер. */
        private long maxBlockMs = 2000;
        private long deliveryTimeoutMs = 5000;
        private long requestTimeoutMs = 3000;
        private long lingerMs = 5;
    }

    @Data
    public static class RateLimit {
        private boolean enabled = true;

        private int perUserMaxRequests = 30;
        private int perUserWindowSeconds = 60;

        private int perIpMaxRequests = 20;
        private int perIpWindowSeconds = 60;

        private int perDeviceMaxRequests = 10;
        private int perDeviceWindowSeconds = 60;

        /** Запросов в окно на весь сервис (все поды, Redis). */
        private int globalMaxRequests = 2000;
        private int publicGlobalMaxRequests = 300;
        private int globalWindowSeconds = 1;
        /** Через сколько повторять после глобального отказа: окно в 1 с отдавать нельзя — все вернутся разом. */
        private int globalRetryAfterSeconds = 30;
        /** После ошибки Redis не трогаем его столько миллисекунд: запросы пропускаются без лимитов. */
        private long redisCooldownMs = 10_000;
    }
}
