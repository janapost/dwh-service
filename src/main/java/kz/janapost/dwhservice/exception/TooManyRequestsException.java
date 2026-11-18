package kz.janapost.dwhservice.exception;

import lombok.Getter;

/**
 * Превышен лимит запросов к эндпоинту. Отдаётся как {@code -429} с {@code data.retryAfterSec}.
 */
@Getter
public class TooManyRequestsException extends RuntimeException {
    private final long retryAfterSec;

    public TooManyRequestsException(long retryAfterSec) {
        super("Too many requests, retry after " + retryAfterSec + " sec");
        this.retryAfterSec = retryAfterSec;
    }
}
