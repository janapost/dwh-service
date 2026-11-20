package kz.janapost.dwhservice.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** AppsFlyer S2S (перенос из client-service, {@code kz.janapost.client-service.appsflyer.*}). */
@Data
@Component
@ConfigurationProperties(prefix = "kz.janapost.dwh-service.appsflyer")
public class AppsflyerProperties {
    private String baseUrl = "https://api3.appsflyer.com";
    private String s2sKey;
    private String androidAppId;
    private String iosAppId;
    /**
     * Выключено — теневой режим: identity и строки журнала сохраняются, в AppsFlyer ничего не уходит, ретрай и
     * очистка AppsFlyer не работают. Строка ждёт включения как {@code FAILED} без попытки.
     */
    private boolean sendEnabled;
    /** Вызов идёт из потока консьюмера Kafka: зависший AppsFlyer не должен останавливать чтение {@code event_s2s}. */
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(10);
    private Retry retry = new Retry();
    private EventCleanup eventCleanup = new EventCleanup();

    @Data
    public static class Retry {
        private int maxAttempts = 3;
        private int batchSize = 50;
    }

    /**
     * Ночная очистка журнала от SENT / SKIPPED. Строка журнала — это ещё и ключ дедупликации, поэтому удаляются только
     * строки старше {@link #retentionDays}, а сервис не опускает срок ниже своего минимума.
     */
    @Data
    public static class EventCleanup {
        /** Удаление необратимо: включать после создания индекса {@code (status, createTime)}. */
        private boolean enabled;
        private int retentionDays = 90;
        private int batchSize = 10_000;
    }
}
