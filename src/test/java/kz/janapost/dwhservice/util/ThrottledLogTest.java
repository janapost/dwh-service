package kz.janapost.dwhservice.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ThrottledLogTest {

    private static final long PERIOD_MS = 60_000;

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final List<String> lines = new ArrayList<>();
    private final AtomicInteger detailsComputed = new AtomicInteger();
    private final ThrottledLog log = new ThrottledLog(PERIOD_MS, now::get, (message, details) -> lines.add(message));

    @Test
    @DisplayName("всплеск ошибок: одна запись за период, детали (стектрейс) считаются только для неё")
    void burst_oneRecordPerPeriod() {
        for (int i = 0; i < 100; i++) {
            log.append("kafka down", this::details);
            now.addAndGet(100);
        }

        assertThat(lines).containsExactly("kafka down");
        assertThat(detailsComputed).hasValue(1);
    }

    @Test
    @DisplayName("после периода — новая запись с числом пропущенных")
    void afterPeriod_recordsSkippedCount() {
        log.append("kafka down", this::details);
        for (int i = 0; i < 99; i++) {
            log.append("kafka down", this::details);
        }

        now.addAndGet(PERIOD_MS);
        log.append("kafka down", this::details);
        now.addAndGet(PERIOD_MS);
        log.append("kafka down", this::details);

        assertThat(lines).containsExactly(
                "kafka down",
                "kafka down (+99 similar skipped since last record)",
                "kafka down");
    }

    private String details() {
        detailsComputed.incrementAndGet();
        return "stack trace";
    }
}
