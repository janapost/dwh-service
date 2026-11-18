package kz.janapost.dwhservice.util;

import kz.janapost.simplelogger.SimpleLogger;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Запись в {@code SimpleLogger} не чаще раза за период: на всплеске ошибок (Kafka / Redis недоступны, каждое
 * приложение повторяет запрос) лог не растёт на каждый запрос. Пропущенные записи считаются, их число
 * дописывается в следующую строку. Для алертов — метрики, а не этот лог.
 * <p>
 * В лог не писать данные клиента (тело пачки, {@code jpid}, IP) — только техническое описание ошибки.
 */
public class ThrottledLog {
    private final long periodMs;
    private final LongSupplier clock;
    private final BiConsumer<String, String> sink;
    /** Раньше этого момента (epoch ms) не пишем. */
    private final AtomicLong nextAllowedAt = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong skipped = new AtomicLong();

    public ThrottledLog(String logName, Class<?> owner, long periodMs, LongSupplier clock) {
        this(periodMs, clock, (message, details) -> SimpleLogger.getInstanceDayly(logName, owner).append(message, details));
    }

    ThrottledLog(long periodMs, LongSupplier clock, BiConsumer<String, String> sink) {
        this.periodMs = periodMs;
        this.clock = clock;
        this.sink = sink;
    }

    /**
     * Пишет, если с прошлой записи прошёл период; иначе только увеличивает счётчик пропущенных.
     * {@code details} (например, стектрейс) вычисляется только при записи.
     *
     * @return {@code true}, если запись сделана
     */
    public boolean append(String message, Supplier<String> details) {
        long now = clock.getAsLong();
        long allowedAt = nextAllowedAt.get();
        if (now < allowedAt || !nextAllowedAt.compareAndSet(allowedAt, now + periodMs)) {
            skipped.incrementAndGet();
            return false;
        }
        long skippedBefore = skipped.getAndSet(0);
        String line = skippedBefore > 0 ? message + " (+" + skippedBefore + " similar skipped since last record)" : message;
        sink.accept(line, details.get());
        return true;
    }
}
