package kz.janapost.dwhservice.exception;

import java.io.IOException;

/**
 * Тело запроса больше допустимого. Наследует {@link IOException}: бросается при чтении тела,
 * когда {@code Content-Length} не передан (chunked). Отдаётся как {@code -413}.
 */
public class PayloadTooLargeException extends IOException {
    public PayloadTooLargeException(long maxBytes) {
        super("Request body is larger than " + maxBytes + " bytes");
    }
}
