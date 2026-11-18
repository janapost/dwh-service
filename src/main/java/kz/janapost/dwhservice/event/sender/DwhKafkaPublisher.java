package kz.janapost.dwhservice.event.sender;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.janapost.dwhservice.config.properties.MobileIngestProperties;
import kz.janapost.dwhservice.model.kafka.DwhKafkaMessage;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Публикует пачку сообщений в Kafka и ждёт подтверждения <b>всей</b> пачки.
 * <p>
 * {@code jpevent.doEvent} здесь не подходит: он проглатывает ошибки, а мобильному приложению нужен
 * честный ответ — приняла ли Kafka пачку, чтобы при ошибке повторить её с теми же {@code eventId}.
 * <p>
 * Свой продюсер (не бин {@code KafkaTemplate}): жёсткие таймауты нужны только здесь, а общий шаблон Spring Boot
 * использует библиотека {@code monitoring}.
 * <p>
 * Поток Tomcat не ждёт подтверждения: метод возвращает future. Синхронно {@code send()} блокирует, пока нет
 * метаданных топика или полон буфер — до {@code max.block.ms} на <b>каждую</b> запись, и при этом не бросает, а
 * возвращает упавший future. Поэтому пачка прерывается на первой такой записи и держит общий дедлайн, который
 * проверяется перед каждой записью. Худший случай для потока — <b>сумма</b>
 * {@code kafka-send-timeout-ms + max.block.ms} (последняя запись начала отправку перед самым дедлайном и ждёт
 * {@code max.block.ms}); по умолчанию 6 + 2 = ~8 с. Без этого было бы {@code max.block.ms × N}.
 */
@Component
public class DwhKafkaPublisher implements DisposableBean {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final DefaultKafkaProducerFactory<String, String> ownProducerFactory;

    @Autowired
    public DwhKafkaPublisher(KafkaProperties kafkaProperties, MobileIngestProperties properties, ObjectMapper objectMapper) {
        validate(properties);
        this.ownProducerFactory = new DefaultKafkaProducerFactory<>(producerConfig(kafkaProperties, properties.getKafka()));
        this.kafkaTemplate = new KafkaTemplate<>(ownProducerFactory);
        this.objectMapper = objectMapper;
    }

    /** Для тестов: готовый шаблон, без своего продюсера. */
    public DwhKafkaPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.ownProducerFactory = null;
    }

    /**
     * Завершается успешно, когда Kafka подтвердила все сообщения; иначе — исключением: ошибка Kafka или
     * сериализации, первая запись, которую продюсер не принял, или истёк общий дедлайн {@code timeoutMs}.
     */
    public CompletableFuture<Void> publish(String topic, List<DwhKafkaMessage> messages, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        List<CompletableFuture<?>> sends = new ArrayList<>(messages.size());
        try {
            for (DwhKafkaMessage message : messages) {
                if (System.nanoTime() - deadline >= 0) {
                    return CompletableFuture.failedFuture(new TimeoutException(
                            "Batch deadline of " + timeoutMs + " ms exceeded after " + sends.size() + " of " + messages.size()));
                }
                CompletableFuture<?> send = kafkaTemplate.send(topic, toJson(message));
                if (send.isCompletedExceptionally()) {
                    // Продюсер не принял запись (нет метаданных, полон буфер): остальные упадут так же, не ждём каждую.
                    return CompletableFuture.allOf(send);
                }
                sends.add(send);
            }
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        long remainingMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
        return CompletableFuture.allOf(sends.toArray(new CompletableFuture<?>[0]))
                .orTimeout(remainingMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void destroy() {
        if (ownProducerFactory != null) {
            ownProducerFactory.destroy();
        }
    }

    private String toJson(DwhKafkaMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Kafka message serialization failed", e);
        }
    }

    private static Map<String, Object> producerConfig(KafkaProperties kafkaProperties, MobileIngestProperties.Kafka kafka) {
        // bootstrap-servers и SASL — общие (spring.kafka.*), таймауты и надёжность — свои.
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildProducerProperties());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, kafka.getAcks());
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, kafka.isIdempotence());
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, kafka.getMaxBlockMs());
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) kafka.getDeliveryTimeoutMs());
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) kafka.getRequestTimeoutMs());
        config.put(ProducerConfig.LINGER_MS_CONFIG, (int) kafka.getLingerMs());
        config.put(ProducerConfig.CLIENT_ID_CONFIG, "dwh-mobile-ingest");
        return config;
    }

    /** Ошибки конфигурации — при старте, а не первым {@code -3} у приложения. */
    public static void validate(MobileIngestProperties properties) {
        MobileIngestProperties.Kafka kafka = properties.getKafka();
        if (kafka.getDeliveryTimeoutMs() < kafka.getLingerMs() + kafka.getRequestTimeoutMs()) {
            throw new IllegalStateException("kz.janapost.dwh-service.mobile.kafka.delivery-timeout-ms must be >= linger-ms + request-timeout-ms");
        }
        if (properties.getKafkaSendTimeoutMs() <= kafka.getDeliveryTimeoutMs()) {
            throw new IllegalStateException("kz.janapost.dwh-service.mobile.kafka-send-timeout-ms must be greater than "
                    + "kafka.delivery-timeout-ms, otherwise the app gets -3 for records Kafka still delivers (duplicates)");
        }
    }
}
