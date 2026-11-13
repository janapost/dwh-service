package kz.janapost.dwhservice.service.impl;

import kz.janapost.commonsdk.bean.GenericResult;
import kz.janapost.dwhservice.config.properties.MobileIngestProperties;
import kz.janapost.dwhservice.event.sender.DwhKafkaPublisher;
import kz.janapost.dwhservice.exception.BadRequestException;
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
import kz.janapost.simplelogger.SimpleLogger;
import kz.janapost.util.ExceptionUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class MobileIngestService_13_11_2026 implements MobileIngestService {
    static final String APP_NAME_PREFIX = "mobile";
    static final String LOG_EVENT_NAME = "log";
    static final String STAT_EVENT_NAME = "stat";
    static final int SCHEMA_VERSION = 1;
    static final int KAFKA_UNAVAILABLE_STATUS = -3;
    static final String CLIENT_TIMESTAMP_KEY = "clientTimestamp";

    private static final String LOG_NAME = "exception-mobile-kafka-publish[delete10]";

    private final MobileIngestProperties properties;
    private final MobileRateLimiter rateLimiter;
    private final DwhKafkaPublisher publisher;
    private final PiiMasker piiMasker;

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
        rateLimiter.checkPublic(ip, fo.getDeviceId());
        long now = System.currentTimeMillis();
        List<DwhKafkaMessage> messages = fo.getItems().stream()
                .map(item -> {
                    DwhKafkaMessage.Sender sender = sender(fo, null, now);
                    putIfPresent(sender.getParams(), "deviceId", fo.getDeviceId());
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
        data.put("eventId", eventId != null ? eventId : UUID.randomUUID().toString());
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
        putIfPresent(sender.getParams(), "appVersion", info.getAppVersion());
        putIfPresent(sender.getParams(), "deviceModel", info.getDeviceModel());
        putIfPresent(sender.getParams(), "osVersion", info.getOsVersion());
        return sender;
    }

    static String appName(String platform) {
        if (platform == null || platform.isBlank()) {
            return APP_NAME_PREFIX;
        }
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

    private CompletableFuture<GenericResult<?>> publish(String topic, List<DwhKafkaMessage> messages) {
        return publisher.publish(topic, messages, properties.getKafkaSendTimeoutMs())
                .handle((ignored, error) -> {
                    if (error == null) {
                        return GenericResult.ok(new IngestResultVO(messages.size()));
                    }
                    SimpleLogger.getInstanceDayly(LOG_NAME, getClass())
                            .append("topic: %s, items: %d".formatted(topic, messages.size()), ExceptionUtil.getStackTrace(error));
                    return new GenericResult<>(KAFKA_UNAVAILABLE_STATUS, "Temporarily unavailable, retry later", null);
                });
    }

    private static CompletableFuture<GenericResult<?>> disabled() {
        return CompletableFuture.completedFuture(GenericResult.ok(new IngestResultVO(0)));
    }
}
