package kz.janapost.dwhservice.event.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.janapost.dwhservice.config.properties.S2SProperties;
import kz.janapost.dwhservice.model.s2s.S2SEvent;
import kz.janapost.dwhservice.model.s2s.S2SIdentity;
import kz.janapost.dwhservice.service.s2s.S2SDispatcher;
import kz.janapost.jpevent.event.EventData;
import kz.janapost.jpevent.event.EventProcessor;
import kz.janapost.jpevent.event.ProcessResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Консьюмер {@code event_s2s} (jpevent): события S2S из client-service → {@link S2SDispatcher}.
 * <ul>
 *     <li>{@code s2s_identity} — ID устройства ({@link S2SIdentity});</li>
 *     <li>{@code s2s_event} — бизнес-событие ({@link S2SEvent}).</li>
 * </ul>
 * Порядок по клиенту: ключ Kafka = jpid, обработка сразу в потоке консьюмера ({@code runFirst}), один поток на
 * партицию ({@code concurrency = 1}) — ID устройства доходит раньше события, отправленного после него.
 * <p>
 * Ошибка провайдера (хранилище недоступно) — {@code RETRY}: jpevent повторит событие, повтор безопасен (дедупликация
 * в журнале провайдера). Повторы прекращаются через {@link #RETRY_WINDOW_MS} — как в {@code CustomEventProcessor}
 * client-service. Битое событие или неизвестный {@code eventName} — {@code ERROR}, без повторов.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class S2SEventProcessor implements EventProcessor {
    public static final String IDENTITY_EVENT = "s2s_identity";
    public static final String BUSINESS_EVENT = "s2s_event";
    static final long RETRY_WINDOW_MS = TimeUnit.HOURS.toMillis(24);
    private static final int THREAD_COUNT = 2;
    private static final int INTERVAL_MS = 1000;
    private static final int FETCH_SIZE = 100;

    private final S2SProperties properties;
    private final S2SDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    @Value("${spring.application.name}")
    private String appName;

    @Override
    public ProcessResult process(EventData eventData) {
        String eventName = eventData.getEventName();
        try {
            if (IDENTITY_EVENT.equals(eventName)) {
                dispatcher.saveIdentity(objectMapper.convertValue(eventData.getData(), S2SIdentity.class));
            } else if (BUSINESS_EVENT.equals(eventName)) {
                dispatcher.send(objectMapper.convertValue(eventData.getData(), S2SEvent.class));
            } else {
                return result(ProcessResult.Type.ERROR, "unknown eventName: " + eventName);
            }
            return result(ProcessResult.Type.FINISH, null);
        } catch (IllegalArgumentException e) {
            // convertValue: данные не разбираются — повтор не поможет
            return result(ProcessResult.Type.ERROR, "invalid " + eventName + " data: " + e.getMessage());
        } catch (RuntimeException e) {
            if (isExpired(eventData)) {
                return result(ProcessResult.Type.ERROR, "retry window expired: " + e);
            }
            log.warn("[S2S] {} will be retried: {}", eventName, e.toString());
            return result(ProcessResult.Type.RETRY, e.toString());
        }
    }

    private static boolean isExpired(EventData eventData) {
        long sentAt = eventData.getSender() != null ? eventData.getSender().getTimestamp() : 0;
        return sentAt > 0 && System.currentTimeMillis() - sentAt > RETRY_WINDOW_MS;
    }

    private static ProcessResult result(ProcessResult.Type type, String text) {
        ProcessResult result = new ProcessResult();
        result.setType(type);
        result.setResult(text);
        return result;
    }

    @Override
    public String topicName() {
        return properties.getTopic();
    }

    @Override
    public String groupName() {
        return appName;
    }

    @Override
    public int threadCount() {
        return THREAD_COUNT;
    }

    @Override
    public int intervalMs() {
        return INTERVAL_MS;
    }

    @Override
    public int processFetchSize() {
        return FETCH_SIZE;
    }

    @Override
    public boolean enabled() {
        return properties.isConsumerEnabled();
    }

    /** Сразу в потоке консьюмера: порядок событий одного клиента сохраняется. */
    @Override
    public boolean runFirst() {
        return true;
    }

    @Override
    public int concurrency() {
        return 1;
    }
}
