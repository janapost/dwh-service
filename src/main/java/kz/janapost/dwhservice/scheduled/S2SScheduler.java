package kz.janapost.dwhservice.scheduled;

import kz.janapost.dwhservice.service.s2s.S2SDispatcher;
import kz.janapost.dwhservice.service.s2s.S2SService;
import kz.janapost.redislock.RedisLock;
import kz.janapost.simplelogger.SimpleLogger;
import kz.janapost.util.ExceptionUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Ретрай и очистка журналов активных S2S-провайдеров. Запуск на всех подах, работает один — по Redis-блокировке
 * своего провайдера. Блокировка берётся с запасом: медленная пачка пропустит следующий запуск, а не наложится на него.
 * Включение ретрая и очистки у каждого провайдера своё ({@code send-enabled}, {@code event-cleanup.enabled}).
 */
@Component
@RequiredArgsConstructor
public class S2SScheduler {
    static final String RETRY_LOCK_PREFIX = "dwh_s2s_retry_";
    static final String CLEANUP_LOCK_PREFIX = "dwh_s2s_cleanup_";
    private static final long LOCK_EXPIRE_MS = 240_000L;
    private static final String LOG_NAME = "exception-s2s-scheduler[delete10]";

    private final S2SDispatcher dispatcher;
    private final RedisLock redisLock;

    @Scheduled(fixedDelayString = "${kz.janapost.dwh-service.s2s.retry-fixed-delay:PT5M}",
            initialDelayString = "${kz.janapost.dwh-service.s2s.retry-initial-delay:PT2M}")
    public void retry() {
        forEachLocked(RETRY_LOCK_PREFIX, S2SService::retryPending);
    }

    @Scheduled(cron = "${kz.janapost.dwh-service.s2s.cleanup-cron:0 * 2-3 * * *}",
            zone = "${kz.janapost.dwh-service.s2s.cleanup-zone:Asia/Almaty}")
    public void cleanup() {
        forEachLocked(CLEANUP_LOCK_PREFIX, S2SService::cleanup);
    }

    private void forEachLocked(String lockPrefix, Consumer<S2SService> action) {
        for (S2SService service : dispatcher.activeServices()) {
            String lockKey = lockPrefix + service.beanName();
            String uniqueId = UUID.randomUUID().toString();
            boolean locked = false;
            try {
                locked = redisLock.tryLock(lockKey, uniqueId, LOCK_EXPIRE_MS);
                if (locked) {
                    action.accept(service);
                }
            } catch (Exception e) {
                // без данных клиента: только провайдер и ошибка
                SimpleLogger.getInstanceDayly(LOG_NAME, getClass())
                        .append(lockKey, ExceptionUtil.getStackTrace(e));
            } finally {
                if (locked) {
                    redisLock.releaseLock(lockKey, uniqueId);
                }
            }
        }
    }
}
