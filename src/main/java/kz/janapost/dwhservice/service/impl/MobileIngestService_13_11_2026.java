package kz.janapost.dwhservice.service.impl;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import kz.janapost.commonsdk.bean.GenericResult;
import kz.janapost.dwhservice.config.properties.MobileIngestProperties;
import kz.janapost.dwhservice.event.sender.DwhKafkaPublisher;
import kz.janapost.dwhservice.exception.BadRequestException;
import kz.janapost.dwhservice.exception.ResultHttpStatus;
import kz.janapost.dwhservice.model.fo.client.v1.ClientLogBatchFO;
import kz.janapost.dwhservice.model.fo.client.v1.ClientLogItemFO;
import kz.janapost.dwhservice.model.fo.client.v1.ClientStatBatchFO;
import kz.janapost.dwhservice.model.fo.client.v1.ClientStatItemFO;
import kz.janapost.dwhservice.model.fo.client.v1.MobileClientInfo;
import kz.janapost.dwhservice.model.fo.pub.v1.PublicLogBatchFO;
import kz.janapost.dwhservice.model.kafka.DwhKafkaMessage;
import kz.janapost.dwhservice.model.vo.IngestResultVO;
import kz.janapost.dwhservice.service.MobileIngestService;
import kz.janapost.dwhservice.service.MobileRateLimiter;
import kz.janapost.dwhservice.util.PiiMasker;
import kz.janapost.dwhservice.util.ThrottledLog;
import kz.janapost.util.ExceptionUtil;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@Service
public class MobileIngestService_13_11_2026 implements MobileIngestService {
    /** Prometheus: {@code dwh_mobile_publish_failed_total{topic}} — для алерта на недоступность Kafka. */
    static final String PUBLISH_FAILED_METRIC = "dwh.mobile.publish.failed";
    static final String APP_NAME_PREFIX = "mobile";
    static final String LOG_EVENT_NAME = "log";
    static final String STAT_EVENT_NAME = "stat";
    static final int SCHEMA_VERSION = 1;
    static final int KAFKA_UNAVAILABLE_STATUS = ResultHttpStatus.TEMPORARILY_UNAVAILABLE;
    static final String CLIENT_TIMESTAMP_KEY = "clientTimestamp";
    /** {@code sender.params.untrusted = true} — запись пришла без токена ({@code /public}). */
    static final String UNTRUSTED_KEY = "untrusted";
    /** {@code sender.params.clientId} — ID установки приложения, во всех трёх эндпоинтах. */
    static final String CLIENT_ID_KEY = "clientId";

    private static final String LOG_NAME = "exception-mobile-kafka-publish[delete10]";
    private static final long PUBLISH_FAILURE_LOG_PERIOD_MS = TimeUnit.MINUTES.toMillis(1);
    /** Работа в колбэке — собрать ответ, изредка строка лога: двух потоков хватает с запасом. */
    private static final int CALLBACK_THREADS = 2;

    private final MobileIngestProperties properties;
    private final MobileRateLimiter rateLimiter;
    private final DwhKafkaPublisher publisher;
    private final PiiMasker piiMasker;
    private final MeterRegistry meterRegistry;
    private final ThrottledLog publishFailureLog =
            new ThrottledLog(LOG_NAME, getClass(), PUBLISH_FAILURE_LOG_PERIOD_MS, System::currentTimeMillis);
    /** Не Spring-бин: бин типа Executor отключил бы {@code applicationTaskExecutor} (MVC async, {@code @Async}). */
    private final ExecutorService callbackExecutor = Executors.newFixedThreadPool(CALLBACK_THREADS, callbackThreadFactory());

    public MobileIngestService_13_11_2026(MobileIngestProperties properties, MobileRateLimiter rateLimiter,
                                          DwhKafkaPublisher publisher, PiiMasker piiMasker, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.publisher = publisher;
        this.piiMasker = piiMasker;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public CompletableFuture<GenericResult<?>> acceptClientLogs(String jpid, ClientLogBatchFO fo) {
        if (!properties.isEnabled()) {
            return disabled();
        }
        checkItemCount(fo.getItems().size(), properties.getMaxItems());
        rateLimiter.checkClient(jpid);
        long now = System.currentTimeMillis();
        List<DwhKafkaMessage> messages = fo.getItems().stream()
                .map(item -> logMessage(item, sender(fo, jpid, now)))
                .toList();
        return publish(properties.getLogTopic(), messages);
    }

    @Override
    public CompletableFuture<GenericResult<?>> acceptClientStats(String jpid, ClientStatBatchFO fo) {
        if (!properties.isEnabled()) {
            return disabled();
        }
        checkItemCount(fo.getItems().size(), properties.getMaxItems());
        rateLimiter.checkClient(jpid);
        long now = System.currentTimeMillis();
        List<DwhKafkaMessage> messages = fo.getItems().stream()
                .map(item -> statMessage(item, sender(fo, jpid, now)))
                .toList();
        return publish(properties.getStatTopic(), messages);
    }

    @Override
    public CompletableFuture<GenericResult<?>> acceptPublicLogs(String ip, PublicLogBatchFO fo) {
        if (!properties.isPublicEnabled()) {
            return disabled();
        }
        checkItemCount(fo.getItems().size(), properties.getPublicMaxItems());
        rateLimiter.checkPublic(ip, fo.getClientId());
        long now = System.currentTimeMillis();
        List<DwhKafkaMessage> messages = fo.getItems().stream()
                .map(item -> {
                    DwhKafkaMessage.Sender sender = sender(fo, null, now);
                    // Без токена: всё содержимое (clientId, appVersion, текст) — со слов клиента.
                    // В ClickHouse это колонка untrusted = 1, аналитика должна это учитывать.
                    sender.getParams().put(UNTRUSTED_KEY, true);
                    return logMessage(item, sender);
                })
                .toList();
        return publish(properties.getLogTopic(), messages);
    }

    private DwhKafkaMessage logMessage(ClientLogItemFO item, DwhKafkaMessage.Sender sender) {
        long eventTime = eventTime(item.getTimestamp(), sender.getTimestamp());
        Map<String, Object> data = baseData(item.getEventId(), eventTime);
        data.put("level", item.getLevel().toUpperCase(Locale.ROOT));
        data.put("message", piiMasker.mask(item.getMessage()));
        putIfPresent(data, "stackTrace", piiMasker.mask(item.getStackTrace()));
        putIfPresent(data, "traceId", item.getTraceId());
        putIfPresent(data, "attributes",
                withClientTimestamp(piiMasker.maskValues(item.getAttributes()), item.getTimestamp(), eventTime));
        return message(properties.getLogTopic(), LOG_EVENT_NAME, sender, data);
    }

    private DwhKafkaMessage statMessage(ClientStatItemFO item, DwhKafkaMessage.Sender sender) {
        long eventTime = eventTime(item.getTimestamp(), sender.getTimestamp());
        Map<String, Object> data = baseData(item.getEventId(), eventTime);
        data.put("metric", item.getMetric());
        data.put("value", item.getValue());
        putIfPresent(data, "tags",
                withClientTimestamp(piiMasker.maskValues(item.getTags()), item.getTimestamp(), eventTime));
        return message(properties.getStatTopic(), STAT_EVENT_NAME, sender, data);
    }

    /**
     * Время записи с устройства, если оно правдоподобно: не старше {@code max-client-timestamp-age-days} и не впереди
     * больше чем на {@code max-client-timestamp-ahead-ms}. Иначе (сбитые часы, секунды вместо мс, разные годы
     * в одной пачке) — время сервера: ClickHouse не получит сотни разных месяцев в одном блоке, а запись —
     * TTL в прошлом.
     */
    long eventTime(Long clientTimestamp, long receivedAt) {
        if (clientTimestamp == null) {
            return receivedAt;
        }
        long oldest = receivedAt - TimeUnit.DAYS.toMillis(properties.getMaxClientTimestampAgeDays());
        long newest = receivedAt + properties.getMaxClientTimestampAheadMs();
        return clientTimestamp >= oldest && clientTimestamp <= newest ? clientTimestamp : receivedAt;
    }

    /** Если время с устройства заменено, исходное значение сохраняется в {@code clientTimestamp}. */
    private static Map<String, String> withClientTimestamp(Map<String, String> values, Long clientTimestamp, long eventTime) {
        if (clientTimestamp == null || clientTimestamp == eventTime) {
            return values;
        }
        Map<String, String> result = values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
        result.put(CLIENT_TIMESTAMP_KEY, String.valueOf(clientTimestamp));
        return result;
    }

    private static Map<String, Object> baseData(String eventId, long eventTime) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventId", eventId); // обязателен (@NotBlank): повтор пачки приходит с теми же eventId
        data.put("schemaVersion", SCHEMA_VERSION);
        data.put("timestamp", eventTime);
        return data;
    }

    private static DwhKafkaMessage message(String topic, String eventName, DwhKafkaMessage.Sender sender,
                                           Map<String, Object> data) {
        DwhKafkaMessage message = new DwhKafkaMessage();
        message.setTopic(topic);
        message.setSender(sender);
        message.setEventName(eventName);
        message.setData(data);
        return message;
    }

    private static DwhKafkaMessage.Sender sender(MobileClientInfo info, String jpid, long now) {
        DwhKafkaMessage.Sender sender = new DwhKafkaMessage.Sender();
        sender.setAppName(appName(info.getPlatform()));
        sender.setJpid(jpid);
        sender.setTimestamp(now);
        putIfPresent(sender.getParams(), CLIENT_ID_KEY, info.getClientId());
        putIfPresent(sender.getParams(), "appVersion", info.getAppVersion());
        putIfPresent(sender.getParams(), "deviceModel", info.getDeviceModel());
        putIfPresent(sender.getParams(), "osVersion", info.getOsVersion());
        return sender;
    }

    /** {@code mobile-ios} / {@code mobile-android}: platform обязателен и проверен валидацией. */
    static String appName(String platform) {
        return APP_NAME_PREFIX + "-" + platform.toLowerCase(Locale.ROOT);
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value instanceof String string && string.isBlank()) {
            return;
        }
        if (value instanceof Map<?, ?> nested && nested.isEmpty()) {
            return;
        }
        if (value != null) {
            map.put(key, value);
        }
    }

    private static void checkItemCount(int count, int max) {
        if (count > max) {
            throw new BadRequestException("items: at most " + max + " per request, got " + count);
        }
    }

    /**
     * Ответ собирается на своём пуле ({@link #callbackExecutor}), а не там, где завершился future: иначе это был бы
     * {@code kafka-producer-network-thread} (он же отправляет все записи) или общий для JVM поток {@code orTimeout},
     * и запись лога на них тормозила бы отправку и чужие таймауты.
     */
    private CompletableFuture<GenericResult<?>> publish(String topic, List<DwhKafkaMessage> messages) {
        return publisher.publish(topic, messages, properties.getKafkaSendTimeoutMs())
                .handleAsync((ignored, error) -> {
                    if (error == null) {
                        return GenericResult.ok(new IngestResultVO(messages.size()));
                    }
                    meterRegistry.counter(PUBLISH_FAILED_METRIC, "topic", topic).increment();
                    // Раз за период и без данных клиента: только топик и размер пачки.
                    publishFailureLog.append("topic: %s, items: %d".formatted(topic, messages.size()),
                            () -> ExceptionUtil.getStackTrace(error));
                    return new GenericResult<>(KAFKA_UNAVAILABLE_STATUS, "Temporarily unavailable, retry later", null);
                }, callbackExecutor);
    }

    @PreDestroy
    void shutdownCallbackExecutor() {
        callbackExecutor.shutdown();
    }

    private static ThreadFactory callbackThreadFactory() {
        CustomizableThreadFactory factory = new CustomizableThreadFactory("dwh-publish-callback-");
        factory.setDaemon(true);
        return factory;
    }

    private static CompletableFuture<GenericResult<?>> disabled() {
        return CompletableFuture.completedFuture(GenericResult.ok(new IngestResultVO(0)));
    }
}
