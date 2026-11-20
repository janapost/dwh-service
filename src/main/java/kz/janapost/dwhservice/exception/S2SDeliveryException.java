package kz.janapost.dwhservice.exception;

import java.util.List;

/** Событие S2S не обработано одним или несколькими провайдерами; консьюмер {@code event_s2s} повторит его. */
public class S2SDeliveryException extends RuntimeException {
    public S2SDeliveryException(List<String> failedBeans, Throwable firstCause) {
        super("S2S providers failed: " + failedBeans, firstCause);
    }
}
