package kz.janapost.dwhservice.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.janapost.commonsdk.bean.GenericResult;
import kz.janapost.dwhservice.config.properties.MobileIngestProperties;
import kz.janapost.dwhservice.event.sender.DwhKafkaPublisher;
import kz.janapost.dwhservice.exception.BadRequestException;
import kz.janapost.dwhservice.model.fo.client.v1.ClientLogBatchFO;
import kz.janapost.dwhservice.model.fo.client.v1.ClientLogItemFO;
import kz.janapost.dwhservice.model.fo.client.v1.ClientStatBatchFO;
import kz.janapost.dwhservice.model.fo.client.v1.ClientStatItemFO;
import kz.janapost.dwhservice.model.fo.pub.v1.PublicLogBatchFO;
import kz.janapost.dwhservice.model.vo.IngestResultVO;
import kz.janapost.dwhservice.service.MobileRateLimiter;
import kz.janapost.dwhservice.util.PiiMasker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Логика приёма: всё настоящее (публикатор, маскирование, сериализация), подменена только граница — {@link KafkaTemplate}.
 * Лимиты частоты выключены и проверяются отдельно в {@code MobileRateLimiterTest}.
 */
class MobileIngestService_13_11_2026Test {

    private static final String JPID = "JP4595925";
    private static final String EVENT_ID = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

    private final ObjectMapper objectMapper = new ObjectMapper();
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private MobileIngestProperties properties;
    private MobileIngestService_13_11_2026 service;

    @BeforeEach
    void setUp() {
        properties = new MobileIngestProperties();
        properties.setEnabled(true);
        properties.setPublicEnabled(true);
        properties.getRateLimit().setEnabled(false);
        service = new MobileIngestService_13_11_2026(
                properties,
                new MobileRateLimiter(properties, mock(StringRedisTemplate.class)),
                new DwhKafkaPublisher(kafkaTemplate, objectMapper),
                new PiiMasker());
    }

    @Test
    @DisplayName("лог после входа: конверт jpevent, jpid из токена, eventId и время из запроса, ПДн замаскированы")
    void clientLogs_publishedAsJpeventEnvelope() throws Exception {
        kafkaAccepts();
        long deviceTime = System.currentTimeMillis() - 3_600_000;
        ClientLogBatchFO fo = logBatch("android", logItem(EVENT_ID, deviceTime, "error", "call +77011234567"));
        fo.setAppVersion("5.12.0");

        GenericResult<?> result = service.acceptClientLogs(JPID, fo).get();

        assertThat(result.getStatus()).isZero();
        assertThat(result.getData()).isEqualTo(new IngestResultVO(1));
        JsonNode message = singleSentMessage(properties.getLogTopic());
        assertThat(message.at("/topic").asText()).isEqualTo("event_dwh_log");
        assertThat(message.at("/eventName").asText()).isEqualTo("log");
        assertThat(message.at("/sender/appName").asText()).isEqualTo("mobile-android");
        assertThat(message.at("/sender/jpid").asText()).isEqualTo(JPID);
        assertThat(message.at("/sender/params/appVersion").asText()).isEqualTo("5.12.0");
        assertThat(message.at("/data/eventId").asText()).isEqualTo(EVENT_ID);
        assertThat(message.at("/data/schemaVersion").asInt()).isEqualTo(1);
        assertThat(message.at("/data/timestamp").asLong()).isEqualTo(deviceTime);
        assertThat(message.at("/data").has("attributes")).isFalse();
        assertThat(message.at("/data/level").asText()).isEqualTo("ERROR");
        assertThat(message.at("/data/message").asText()).isEqualTo("call " + "***PHONE***");
    }

    @Test
    @DisplayName("сбитые часы (1970, на год вперёд): время сервера, исходное — в attributes.clientTimestamp")
    void implausibleDeviceTime_replacedWithServerTime() throws Exception {
        kafkaAccepts();
        long before = System.currentTimeMillis();
        long yearAhead = before + 365L * 24 * 3_600_000;
        ClientLogItemFO epochStart = logItem(null, 1000L, "INFO", "a");
        epochStart.setAttributes(Map.of("screen", "home"));

        service.acceptClientLogs(JPID, logBatch("ios", epochStart, logItem(null, yearAhead, "INFO", "b"))).get();

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, times(2)).send(eq("event_dwh_log"), json.capture());
        JsonNode first = objectMapper.readTree(json.getAllValues().get(0));
        JsonNode second = objectMapper.readTree(json.getAllValues().get(1));
        assertThat(first.at("/data/timestamp").asLong()).isGreaterThanOrEqualTo(before);
        assertThat(first.at("/data/attributes/clientTimestamp").asText()).isEqualTo("1000");
        assertThat(first.at("/data/attributes/screen").asText()).isEqualTo("home");
        assertThat(second.at("/data/timestamp").asLong()).isBetween(before, System.currentTimeMillis());
        assertThat(second.at("/data/attributes/clientTimestamp").asText()).isEqualTo(String.valueOf(yearAhead));
    }

    @Test
    @DisplayName("граница окна времени: max-client-timestamp-age-days — включительно, старше — время сервера")
    void deviceTimeWindowBoundary() {
        long now = 1_800_000_000_000L;
        long ageMs = 30L * 24 * 3_600_000;

        assertThat(service.eventTime(now - ageMs, now)).isEqualTo(now - ageMs);
        assertThat(service.eventTime(now - ageMs - 1, now)).isEqualTo(now);
        assertThat(service.eventTime(now + 86_400_000L, now)).isEqualTo(now + 86_400_000L);
        assertThat(service.eventTime(now + 86_400_001L, now)).isEqualTo(now);
        assertThat(service.eventTime(null, now)).isEqualTo(now);
    }

    @Test
    @DisplayName("статистика со сбитыми часами: исходное время — в tags.clientTimestamp")
    void implausibleDeviceTime_stats() throws Exception {
        kafkaAccepts();
        ClientStatItemFO item = new ClientStatItemFO();
        item.setMetric("m");
        item.setValue(1.0);
        item.setTimestamp(1000L);
        ClientStatBatchFO fo = new ClientStatBatchFO();
        fo.setItems(List.of(item));

        service.acceptClientStats(JPID, fo).get();

        assertThat(singleSentMessage(properties.getStatTopic()).at("/data/tags/clientTimestamp").asText()).isEqualTo("1000");
    }

    @Test
    @DisplayName("необязательные поля не пришли: eventId — новый UUID, timestamp — время сервера, платформа — mobile")
    void clientLogs_optionalFieldsMissing() throws Exception {
        kafkaAccepts();
        long before = System.currentTimeMillis();

        service.acceptClientLogs(JPID, logBatch(null, logItem(null, null, "INFO", "started"))).get();

        JsonNode message = singleSentMessage(properties.getLogTopic());
        assertThat(message.at("/sender/appName").asText()).isEqualTo("mobile");
        assertThat(message.at("/data/eventId").asText()).matches("[0-9a-f-]{36}");
        assertThat(message.at("/data/timestamp").asLong()).isGreaterThanOrEqualTo(before);
        assertThat(message.at("/data").has("stackTrace")).isFalse();
        assertThat(message.at("/sender/params").size()).isZero();
    }

    @Test
    @DisplayName("пачка: одно сообщение на запись, ответ после подтверждения всех")
    void clientLogs_eachItemIsOwnMessage() throws Exception {
        kafkaAccepts();

        GenericResult<?> result = service.acceptClientLogs(JPID, logBatch("ios",
                logItem(null, null, "INFO", "a"), logItem(null, null, "WARN", "b"), logItem(null, null, "ERROR", "c"))).get();

        assertThat(result.getData()).isEqualTo(new IngestResultVO(3));
        verify(kafkaTemplate, times(3)).send(eq("event_dwh_log"), anyString());
    }

    @Test
    @DisplayName("Kafka не подтвердила пачку — статус -3, приложение повторит")
    void kafkaFailure_returnsRetryStatus() throws Exception {
        when(kafkaTemplate.send(anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        GenericResult<?> result = service.acceptClientLogs(JPID, logBatch("android", logItem(null, null, "INFO", "x"))).get();

        assertThat(result.getStatus()).isEqualTo(MobileIngestService_13_11_2026.KAFKA_UNAVAILABLE_STATUS);
    }

    @Test
    @DisplayName("KafkaTemplate.send бросил синхронно (продюсер закрыт, сериализация) — тоже -3, а не 500")
    void kafkaSyncFailure_returnsRetryStatus() throws Exception {
        when(kafkaTemplate.send(anyString(), anyString())).thenThrow(new IllegalStateException("producer closed"));

        GenericResult<?> result = service.acceptClientLogs(JPID, logBatch("android", logItem(null, null, "INFO", "x"))).get();

        assertThat(result.getStatus()).isEqualTo(MobileIngestService_13_11_2026.KAFKA_UNAVAILABLE_STATUS);
    }

    @Test
    @DisplayName("Kafka недоступна: send ждёт max.block.ms и возвращает упавший future — пачка из 100 прерывается на первой записи")
    void kafkaMetadataUnavailable_batchStopsAtFirstRecord() throws Exception {
        // Как настоящий KafkaProducer 3.4: при отсутствии метаданных ждёт max.block.ms и не бросает, а отдаёт FutureFailure.
        when(kafkaTemplate.send(anyString(), anyString())).thenAnswer(invocation -> {
            Thread.sleep(200);
            return CompletableFuture.failedFuture(new org.apache.kafka.common.errors.TimeoutException("metadata"));
        });
        ClientLogItemFO[] items = new ClientLogItemFO[100];
        for (int i = 0; i < items.length; i++) {
            items[i] = logItem(null, null, "INFO", "x" + i);
        }

        long start = System.nanoTime();
        GenericResult<?> result = service.acceptClientLogs(JPID, logBatch("android", items)).get();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.getStatus()).isEqualTo(MobileIngestService_13_11_2026.KAFKA_UNAVAILABLE_STATUS);
        assertThat(elapsedMs).isLessThan(1000);
        verify(kafkaTemplate, times(1)).send(anyString(), anyString());
    }

    @Test
    @DisplayName("общий дедлайн пачки: записи принимаются медленно — остальные не отправляются, ответ -3")
    void batchDeadline_stopsSendingRemainingRecords() throws Exception {
        properties.setKafkaSendTimeoutMs(300);
        when(kafkaTemplate.send(anyString(), anyString())).thenAnswer(invocation -> {
            Thread.sleep(100);
            return new CompletableFuture<>(); // принято продюсером, подтверждения нет
        });
        ClientLogItemFO[] items = new ClientLogItemFO[50];
        for (int i = 0; i < items.length; i++) {
            items[i] = logItem(null, null, "INFO", "x" + i);
        }

        long start = System.nanoTime();
        GenericResult<?> result = service.acceptClientLogs(JPID, logBatch("android", items)).get();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.getStatus()).isEqualTo(MobileIngestService_13_11_2026.KAFKA_UNAVAILABLE_STATUS);
        assertThat(elapsedMs).isLessThan(1000);
        verify(kafkaTemplate, org.mockito.Mockito.atMost(5)).send(anyString(), anyString());
    }

    @Test
    @DisplayName("конфигурация: kafka-send-timeout-ms не больше delivery-timeout-ms — сервис не стартует")
    void sendTimeoutMustExceedDeliveryTimeout() {
        properties.setKafkaSendTimeoutMs(5000);
        properties.getKafka().setDeliveryTimeoutMs(5000);

        assertThatThrownBy(() -> DwhKafkaPublisher.validate(properties)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("конфигурация: значения по умолчанию согласованы")
    void defaultKafkaSettingsAreConsistent() {
        DwhKafkaPublisher.validate(new MobileIngestProperties());
    }

    @Test
    @DisplayName("больше max-items записей — ошибка запроса, в Kafka ничего не уходит")
    void tooManyItems_rejected() {
        properties.setMaxItems(2);
        ClientLogBatchFO fo = logBatch("ios",
                logItem(null, null, "INFO", "a"), logItem(null, null, "INFO", "b"), logItem(null, null, "INFO", "c"));

        assertThatThrownBy(() -> service.acceptClientLogs(JPID, fo)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @DisplayName("приём выключен — ok с accepted=0, в Kafka ничего не уходит")
    void disabled_acceptsNothing() throws Exception {
        properties.setEnabled(false);
        properties.setPublicEnabled(false);

        GenericResult<?> client = service.acceptClientLogs(JPID, logBatch("ios", logItem(null, null, "INFO", "a"))).get();
        PublicLogBatchFO publicFo = new PublicLogBatchFO();
        publicFo.setItems(List.of(logItem(null, null, "INFO", "a")));
        GenericResult<?> pub = service.acceptPublicLogs("1.2.3.4", publicFo).get();

        assertThat(client.getData()).isEqualTo(new IngestResultVO(0));
        assertThat(pub.getData()).isEqualTo(new IngestResultVO(0));
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @DisplayName("статистика: metric и value, теги с замаскированными значениями, топик event_dwh_stat")
    void clientStats_published() throws Exception {
        kafkaAccepts();
        ClientStatItemFO item = new ClientStatItemFO();
        item.setMetric("screen_load_ms");
        item.setValue(812.0);
        item.setTags(Map.of("screen", "home", "phone", "+77011234567"));
        ClientStatBatchFO fo = new ClientStatBatchFO();
        fo.setPlatform("ios");
        fo.setItems(List.of(item));

        service.acceptClientStats(JPID, fo).get();

        JsonNode message = singleSentMessage(properties.getStatTopic());
        assertThat(message.at("/eventName").asText()).isEqualTo("stat");
        assertThat(message.at("/data/metric").asText()).isEqualTo("screen_load_ms");
        assertThat(message.at("/data/value").asDouble()).isEqualTo(812.0);
        assertThat(message.at("/data/tags/screen").asText()).isEqualTo("home");
        assertThat(message.at("/data/tags/phone").asText()).isEqualTo("***PHONE***");
    }

    @Test
    @DisplayName("лог до входа: без jpid, deviceId в sender.params")
    void publicLogs_noJpidAndDeviceId() throws Exception {
        kafkaAccepts();
        PublicLogBatchFO fo = new PublicLogBatchFO();
        fo.setPlatform("android");
        fo.setDeviceId("install-42");
        fo.setItems(List.of(logItem(null, null, "ERROR", "crash on start")));

        service.acceptPublicLogs("95.56.12.34", fo).get();

        JsonNode message = singleSentMessage(properties.getLogTopic());
        assertThat(message.at("/sender/jpid").isNull()).isTrue();
        assertThat(message.at("/sender/params/deviceId").asText()).isEqualTo("install-42");
    }

    @Test
    @DisplayName("до входа своё ограничение пачки — public-max-items")
    void publicLogs_ownItemLimit() {
        properties.setPublicMaxItems(1);
        PublicLogBatchFO fo = new PublicLogBatchFO();
        fo.setItems(List.of(logItem(null, null, "INFO", "a"), logItem(null, null, "INFO", "b")));

        assertThatThrownBy(() -> service.acceptPublicLogs("1.2.3.4", fo)).isInstanceOf(BadRequestException.class);
    }

    private void kafkaAccepts() {
        CompletableFuture<SendResult<String, String>> ok = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(anyString(), anyString())).thenReturn(ok);
    }

    private JsonNode singleSentMessage(String topic) throws Exception {
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(topic), json.capture());
        return objectMapper.readTree(json.getValue());
    }

    private static ClientLogBatchFO logBatch(String platform, ClientLogItemFO... items) {
        ClientLogBatchFO fo = new ClientLogBatchFO();
        fo.setPlatform(platform);
        fo.setItems(new ArrayList<>(List.of(items)));
        return fo;
    }

    private static ClientLogItemFO logItem(String eventId, Long timestamp, String level, String message) {
        ClientLogItemFO item = new ClientLogItemFO();
        item.setEventId(eventId);
        item.setTimestamp(timestamp);
        item.setLevel(level);
        item.setMessage(message);
        return item;
    }
}
