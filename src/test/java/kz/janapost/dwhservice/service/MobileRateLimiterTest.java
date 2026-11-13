package kz.janapost.dwhservice.service;

import io.lettuce.core.ClientOptions;
import kz.janapost.dwhservice.config.properties.MobileIngestProperties;
import kz.janapost.dwhservice.exception.TooManyRequestsException;
import kz.janapost.dwhservice.testsupport.DockerApiVersionExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Лимиты эндпоинтов мобильного приложения на реальном Redis (Testcontainers), по образцу
 * {@code AppsflyerSyncRateLimiterTest} в client-service. Требуется запущенный Docker; без него класс пропускается.
 */
@ExtendWith(DockerApiVersionExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class MobileRateLimiterTest {

    private static final String JPID = "JP1000001";
    private static final String IP = "95.56.12.34";
    private static final String DEVICE_ID = "device-1";

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;

    private StringRedisTemplate redis;
    private MobileIngestProperties properties;
    private MobileIngestProperties.RateLimit limit;
    private MobileRateLimiter limiter;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        redis = new StringRedisTemplate(connectionFactory);
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        properties = new MobileIngestProperties();
        limit = properties.getRateLimit();
        limit.setGlobalWindowSeconds(60); // окно в 1 с истекло бы посреди теста
        limiter = new MobileRateLimiter(properties, redis);
    }

    @Test
    @DisplayName("после входа: 30 запросов клиента за окно проходят, 31-й — отказ до конца окна клиента")
    void client_perUserLimit() {
        passesClient(JPID, 30);

        assertThat(retryAfterClient(JPID)).isBetween(1L, 60L);
    }

    @Test
    @DisplayName("после входа: отказ по лимиту клиента не расходует общий лимит")
    void client_perUserRejection_doesNotConsumeGlobal() {
        passesClient(JPID, 30);
        for (int i = 0; i < 5; i++) {
            retryAfterClient(JPID);
        }

        assertThat(redis.opsForValue().get(MobileRateLimiter.GLOBAL_KEY)).isEqualTo("30");
    }

    @Test
    @DisplayName("после входа: общий лимит на всех клиентов, отказ — с globalRetryAfterSeconds")
    void client_globalLimit() {
        limit.setGlobalMaxRequests(2);

        passesClient("JP1", 1);
        passesClient("JP2", 1);

        assertThat(retryAfterClient("JP3")).isEqualTo(30);
    }

    @Test
    @DisplayName("до входа: 20 запросов с IP проходят, 21-й — отказ")
    void public_perIpLimit() {
        passesPublic(IP, null, 20);

        assertThat(retryAfterPublic(IP, null)).isBetween(1L, 60L);
    }

    @Test
    @DisplayName("до входа: лимит устройства срабатывает раньше лимита IP")
    void public_perDeviceLimit() {
        passesPublic(IP, DEVICE_ID, 10);

        assertThat(retryAfterPublic(IP, DEVICE_ID)).isBetween(1L, 60L);
        passesPublic(IP, "device-2", 1);
    }

    @Test
    @DisplayName("до входа: общий лимит не зависит от лимита после входа")
    void public_globalLimitIsSeparate() {
        limit.setPublicGlobalMaxRequests(1);
        passesPublic("10.0.0.1", null, 1);

        assertThat(retryAfterPublic("10.0.0.2", null)).isEqualTo(30);
        passesClient(JPID, 1);
    }

    @Test
    @DisplayName("недоступный Redis не блокирует запросы")
    void redisDown_failsOpen() {
        StringRedisTemplate brokenRedis = mock(StringRedisTemplate.class, invocation -> {
            throw new QueryTimeoutException("redis is down");
        });
        MobileRateLimiter brokenLimiter = new MobileRateLimiter(properties, brokenRedis);

        for (int i = 0; i < 50; i++) {
            assertThatCode(() -> brokenLimiter.checkClient(JPID)).doesNotThrowAnyException();
            assertThatCode(() -> brokenLimiter.checkPublic(IP, DEVICE_ID)).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("Redis «завис» (контейнер на паузе): при timeout 300 мс запрос пропускается быстрее 1 с, а не через 60 с")
    void redisHangs_failsOpenWithinCommandTimeout() {
        // Те же настройки клиента, что задают spring.data.redis.timeout и RedisConfig (REJECT_COMMANDS).
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(300))
                .clientOptions(ClientOptions.builder()
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .build())
                .build();
        LettuceConnectionFactory hangingFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)), clientConfig);
        hangingFactory.afterPropertiesSet();
        MobileRateLimiter hangingLimiter = new MobileRateLimiter(properties, new StringRedisTemplate(hangingFactory));
        hangingLimiter.checkClient(JPID); // соединение установлено до «зависания»

        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            long start = System.nanoTime();
            assertThatCode(() -> hangingLimiter.checkClient(JPID)).doesNotThrowAnyException();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(elapsedMs).isLessThan(1000);
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
            hangingFactory.destroy();
        }
    }

    @Test
    @DisplayName("rate-limit.enabled=false — Redis не трогаем")
    void disabled_skipsRedis() {
        limit.setEnabled(false);
        StringRedisTemplate untouchedRedis = mock(StringRedisTemplate.class);
        MobileRateLimiter disabledLimiter = new MobileRateLimiter(properties, untouchedRedis);

        for (int i = 0; i < 50; i++) {
            disabledLimiter.checkClient(JPID);
            disabledLimiter.checkPublic(IP, DEVICE_ID);
        }
        verifyNoInteractions(untouchedRedis);
    }

    private void passesClient(String jpid, int times) {
        for (int i = 0; i < times; i++) {
            assertThatCode(() -> limiter.checkClient(jpid)).doesNotThrowAnyException();
        }
    }

    private void passesPublic(String ip, String deviceId, int times) {
        for (int i = 0; i < times; i++) {
            assertThatCode(() -> limiter.checkPublic(ip, deviceId)).doesNotThrowAnyException();
        }
    }

    private long retryAfterClient(String jpid) {
        try {
            limiter.checkClient(jpid);
        } catch (TooManyRequestsException e) {
            return e.getRetryAfterSec();
        }
        throw new AssertionError("expected TooManyRequestsException");
    }

    private long retryAfterPublic(String ip, String deviceId) {
        try {
            limiter.checkPublic(ip, deviceId);
        } catch (TooManyRequestsException e) {
            return e.getRetryAfterSec();
        }
        throw new AssertionError("expected TooManyRequestsException");
    }
}
