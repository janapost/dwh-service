package kz.janapost.dwhservice.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * S2S: события client-service из Kafka {@code event_s2s} отправляются провайдерам (AppsFlyer, позже Adjust).
 * Провайдер — Spring-бин {@code S2SService} с именем; какие из них работают, задают {@link #defaultBean}
 * и {@link #parallelBeans} (проверяются при старте).
 */
@Data
@Component
@ConfigurationProperties(prefix = "kz.janapost.dwh-service.s2s")
public class S2SProperties {
    /** Выключено — {@code event_s2s} не читается. */
    private boolean consumerEnabled;
    private String topic = "event_s2s";
    /** Основной провайдер: имя бина, например {@code appsflyer_s2s_sender}. */
    private String defaultBean = "appsflyer_s2s_sender";
    /** Провайдеры, которые получают те же события параллельно (на время перехода). */
    private List<String> parallelBeans = new ArrayList<>();
}
