package kz.janapost.dwhservice.service;

import kz.janapost.dwhservice.config.properties.MobileIngestProperties;
import kz.janapost.dwhservice.exception.TooManyRequestsException;
import kz.janapost.simplelogger.SimpleLogger;
import kz.janapost.util.ExceptionUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Лимиты эндпоинтов мобильного приложения. Схема как у {@code AppsflyerSyncRateLimiter} в client-service
 * (Redis + Lua, общий счётчик на все поды, fail-open): это потолок нагрузки, а не защита от перебора.
 * <ul>
 *     <li>после входа: {@code dwh-mobile-rl:user:{jpid}}, затем {@code dwh-mobile-rl:global};</li>
 *     <li>до входа: {@code dwh-mobile-rl:ip:{ip}}, {@code dwh-mobile-rl:device:{deviceId}} (если пришёл),
 *         затем {@code dwh-mobile-rl:public-global}.</li>
 * </ul>
 * Счётчики проверяются по порядку: превысивший личный лимит клиент не тратит общий.
 * <p>
 * <b>Недоступный Redis запрос не блокирует</b>, и это ограничено по времени:
 * <ul>
 *     <li>соединение есть, Redis завис или упал — команда отваливается по {@code spring.data.redis.timeout}
 *         или сразу ({@code REJECT_COMMANDS}, см. {@code RedisConfig});</li>
 *     <li>после любой ошибки Redis не трогаем {@code rate-limit.redis-cooldown-ms} — запросы сразу пропускаются,
 *         а лог «Redis недоступен» пишется раз за период, а не на каждый запрос;</li>
 *     <li>пока Redis не подтверждён живым (старт пода, после паузы), в него идёт <b>одна</b> проба, остальные
 *         запросы пропускаются. Иначе при «чёрной дыре» (пакеты теряются) они встали бы в очередь на синхронизированное
 *         создание общего соединения ({@code LettuceConnectionFactory.SharedConnection}) и по очереди ждали
 *         {@code connect-timeout} каждый.</li>
 * </ul>
 */
@Service
public class MobileRateLimiter {
    static final String USER_KEY_PREFIX = "dwh-mobile-rl:user:";
    static final String IP_KEY_PREFIX = "dwh-mobile-rl:ip:";
    static final String DEVICE_KEY_PREFIX = "dwh-mobile-rl:device:";
    static final String GLOBAL_KEY = "dwh-mobile-rl:global";
    static final String PUBLIC_GLOBAL_KEY = "dwh-mobile-rl:public-global";

    private static final String LOG_NAME = "mobile-rate-limit[delete10]";

    /**
     * Атомарно по всем ключам по порядку: INCR, TTL окна при первом запросе; превышен лимит — вернуть
     * retryAfterSec и остальные ключи не трогать. Возвращает {@code 0}, если запрос разрешён.
     * <p>
     * KEYS: ключи по порядку. ARGV: на каждый ключ тройка window, max, retryAfter
     * ({@code retryAfter = 0} — вернуть остаток TTL окна).
     */
    private static final String CHECK_AND_COUNT_LUA = """
            for i = 1, #KEYS do
                local base = (i - 1) * 3
                local window = tonumber(ARGV[base + 1])
                local max = tonumber(ARGV[base + 2])
                local retryAfter = tonumber(ARGV[base + 3])
                local cnt = redis.call('INCR', KEYS[i])
                if cnt == 1 or redis.call('TTL', KEYS[i]) < 0 then
                    redis.call('EXPIRE', KEYS[i], window)
                end
                if cnt > max then
                    if retryAfter > 0 then
                        return retryAfter
                    end
                    local ttl = redis.call('TTL', KEYS[i])
                    if ttl < 1 then
                        ttl = window
                    end
                    return ttl
                end
            end
            return 0
            """;

    private static final RedisScript<Long> CHECK_AND_COUNT_SCRIPT = RedisScript.of(CHECK_AND_COUNT_LUA, Long.class);

    private final MobileIngestProperties properties;
    private final StringRedisTemplate redis;
    private final LongSupplier clock;

    /** Redis подтверждён живым последним успешным вызовом. */
    private volatile boolean redisHealthy;
    /** До этого момента (epoch ms) в Redis не ходим. */
    private final AtomicLong redisSkipUntil = new AtomicLong();
    /** Идёт ли единственная проба Redis, пока он не подтверждён живым. */
    private final AtomicBoolean probeInFlight = new AtomicBoolean();

    @Autowired
    public MobileRateLimiter(MobileIngestProperties properties, StringRedisTemplate redis) {
        this(properties, redis, System::currentTimeMillis);
    }

    MobileRateLimiter(MobileIngestProperties properties, StringRedisTemplate redis, LongSupplier clock) {
        this.properties = properties;
        this.redis = redis;
        this.clock = clock;
    }

    /** Запрос после входа. Бросает {@link TooManyRequestsException}, если превышен лимит клиента или общий. */
    public void checkClient(String jpid) {
        MobileIngestProperties.RateLimit limit = properties.getRateLimit();
        if (!limit.isEnabled() || jpid == null) {
            return;
        }
        Limits limits = new Limits()
                .add(USER_KEY_PREFIX + jpid, limit.getPerUserWindowSeconds(), limit.getPerUserMaxRequests(), 0)
                .add(GLOBAL_KEY, limit.getGlobalWindowSeconds(), limit.getGlobalMaxRequests(),
                        limit.getGlobalRetryAfterSeconds());
        check(limits);
    }

    /** Запрос до входа. Бросает {@link TooManyRequestsException}, если превышен лимит IP, устройства или общий. */
    public void checkPublic(String ip, String deviceId) {
        MobileIngestProperties.RateLimit limit = properties.getRateLimit();
        if (!limit.isEnabled()) {
            return;
        }
        Limits limits = new Limits()
                .add(IP_KEY_PREFIX + (ip == null ? "unknown" : ip),
                        limit.getPerIpWindowSeconds(), limit.getPerIpMaxRequests(), 0);
        if (deviceId != null && !deviceId.isBlank()) {
            limits.add(DEVICE_KEY_PREFIX + deviceId,
                    limit.getPerDeviceWindowSeconds(), limit.getPerDeviceMaxRequests(), 0);
        }
        limits.add(PUBLIC_GLOBAL_KEY, limit.getGlobalWindowSeconds(), limit.getPublicGlobalMaxRequests(),
                limit.getGlobalRetryAfterSeconds());
        check(limits);
    }

    private void check(Limits limits) {
        long retryAfterSec = countSafely(limits);
        if (retryAfterSec > 0) {
            throw new TooManyRequestsException(retryAfterSec);
        }
    }

    /** Возвращает retryAfterSec; {@code 0} — запрос разрешён, в том числе когда Redis недоступен. */
    private long countSafely(Limits limits) {
        if (clock.getAsLong() < redisSkipUntil.get()) {
            return 0;
        }
        boolean probe = !redisHealthy;
        if (probe && !probeInFlight.compareAndSet(false, true)) {
            return 0; // Redis проверяет другой запрос — этот не ждёт его
        }
        try {
            Long result = redis.execute(CHECK_AND_COUNT_SCRIPT, limits.keys, limits.args.toArray());
            redisHealthy = true;
            return result == null ? 0 : result;
        } catch (RuntimeException e) {
            onRedisFailure(e);
            return 0;
        } finally {
            if (probe) {
                probeInFlight.set(false);
            }
        }
    }

    private void onRedisFailure(RuntimeException e) {
        redisHealthy = false;
        long now = clock.getAsLong();
        long previous = redisSkipUntil.getAndSet(now + properties.getRateLimit().getRedisCooldownMs());
        if (previous <= now) {
            // Только тот, кто открыл паузу: лог раз за период, без данных клиента.
            SimpleLogger.getInstanceDayly(LOG_NAME, getClass())
                    .append("redis unavailable, requests are allowed without limits for "
                            + properties.getRateLimit().getRedisCooldownMs() + " ms", ExceptionUtil.getStackTrace(e));
        }
    }

    private static final class Limits {
        private final List<String> keys = new ArrayList<>();
        private final List<String> args = new ArrayList<>();

        private Limits add(String key, int windowSeconds, int maxRequests, int retryAfterSeconds) {
            keys.add(key);
            args.add(String.valueOf(windowSeconds));
            args.add(String.valueOf(maxRequests));
            args.add(String.valueOf(retryAfterSeconds));
            return this;
        }
    }
}
