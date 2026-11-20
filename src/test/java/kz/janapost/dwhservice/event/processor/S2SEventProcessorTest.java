package kz.janapost.dwhservice.event.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.janapost.dwhservice.config.properties.S2SProperties;
import kz.janapost.dwhservice.exception.S2SDeliveryException;
import kz.janapost.dwhservice.model.s2s.S2SEvent;
import kz.janapost.dwhservice.model.s2s.S2SEventType;
import kz.janapost.dwhservice.model.s2s.S2SIdentity;
import kz.janapost.dwhservice.service.s2s.S2SDispatcher;
import kz.janapost.dwhservice.service.s2s.S2SService;
import kz.janapost.jpevent.event.EventData;
import kz.janapost.jpevent.event.ProcessResult;
import kz.janapost.jpevent.event.Sender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Разбор событий {@code event_s2s} в том виде, в каком их публикует client-service (конверт jpevent, data — JSON). */
class S2SEventProcessorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RecordingDispatcher dispatcher = new RecordingDispatcher();
    private final S2SEventProcessor processor = new S2SEventProcessor(new S2SProperties(), dispatcher, objectMapper);

    @Test
    @DisplayName("s2s_event: data из JSON (Map) разбирается в S2SEvent, включая заказы оплаты")
    void businessEvent() throws Exception {
        Object data = objectMapper.readValue("""
                {"type":"PURCHASE","jpid":"JP1","eventTime":1762934400123,"paymentId":"PAY-1",
                 "orders":[{"id":"O-1","usdValue":25000},{"id":"O-2","usdValue":null}],"unknownField":1}
                """, Map.class);

        ProcessResult result = processor.process(eventData("s2s_event", data, System.currentTimeMillis()));

        assertThat(result.getType()).isEqualTo(ProcessResult.Type.FINISH);
        S2SEvent event = dispatcher.events.get(0);
        assertThat(event.type()).isEqualTo(S2SEventType.PURCHASE);
        assertThat(event.eventTime()).isEqualTo(1_762_934_400_123L);
        assertThat(event.orders()).hasSize(2);
        assertThat(event.orders().get(1).usdValue()).isNull();
    }

    @Test
    @DisplayName("s2s_identity: deepLinkData в snake_case, как его присылает приложение")
    void identityEvent() throws Exception {
        Object data = objectMapper.readValue("""
                {"jpid":"JP1","appsflyerId":"1712345678901-1","deviceOs":"ios",
                 "deepLinkData":{"media_source":"google","is_first_launch":true},"userCreateTime":1000}
                """, Map.class);

        ProcessResult result = processor.process(eventData("s2s_identity", data, System.currentTimeMillis()));

        assertThat(result.getType()).isEqualTo(ProcessResult.Type.FINISH);
        S2SIdentity identity = dispatcher.identities.get(0);
        assertThat(identity.deepLinkData().getMediaSource()).isEqualTo("google");
        assertThat(identity.deepLinkData().getIsFirstLaunch()).isTrue();
        assertThat(identity.userCreateTime()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("неизвестный eventName или битые данные — ERROR без повторов")
    void unknownOrInvalidIsError() {
        assertThat(processor.process(eventData("s2s_unknown", Map.of(), System.currentTimeMillis())).getType())
                .isEqualTo(ProcessResult.Type.ERROR);
        assertThat(processor.process(eventData("s2s_event", Map.of("type", "NOT_A_TYPE"), System.currentTimeMillis())).getType())
                .isEqualTo(ProcessResult.Type.ERROR);
        assertThat(dispatcher.events).isEmpty();
    }

    @Test
    @DisplayName("провайдер упал — RETRY; через сутки после отправки — ERROR, повторы прекращаются")
    void providerFailureRetriedWithinWindow() {
        dispatcher.failure = new S2SDeliveryException(List.of("appsflyer_s2s_sender"), new IllegalStateException("mongo"));
        Map<String, Object> data = Map.of("type", "ORDER_CREATED", "jpid", "JP1", "orderId", "O-1");

        ProcessResult fresh = processor.process(eventData("s2s_event", data, System.currentTimeMillis()));
        ProcessResult old = processor.process(eventData("s2s_event", data,
                System.currentTimeMillis() - S2SEventProcessor.RETRY_WINDOW_MS - 1));

        assertThat(fresh.getType()).isEqualTo(ProcessResult.Type.RETRY);
        assertThat(old.getType()).isEqualTo(ProcessResult.Type.ERROR);
    }

    @Test
    @DisplayName("порядок по клиенту: обработка в потоке консьюмера, один поток на партицию; по умолчанию выключен")
    void orderingAndDefaults() {
        assertThat(processor.runFirst()).isTrue();
        assertThat(processor.concurrency()).isEqualTo(1);
        assertThat(processor.topicName()).isEqualTo("event_s2s");
        assertThat(processor.enabled()).isFalse();
    }

    private static EventData eventData(String eventName, Object data, long sentAt) {
        Sender sender = new Sender();
        sender.setAppName("service");
        sender.setJpid("JP1");
        sender.setTimestamp(sentAt);
        EventData eventData = new EventData();
        eventData.setTopic("event_s2s");
        eventData.setSender(sender);
        eventData.setEventName(eventName);
        eventData.setData(data);
        return eventData;
    }

    private static final class RecordingDispatcher implements S2SDispatcher {
        private final List<S2SEvent> events = new ArrayList<>();
        private final List<S2SIdentity> identities = new ArrayList<>();
        private RuntimeException failure;

        @Override
        public void send(S2SEvent event) {
            if (failure != null) {
                throw failure;
            }
            events.add(event);
        }

        @Override
        public void saveIdentity(S2SIdentity identity) {
            identities.add(identity);
        }

        @Override
        public List<S2SService> activeServices() {
            return List.of();
        }
    }
}
