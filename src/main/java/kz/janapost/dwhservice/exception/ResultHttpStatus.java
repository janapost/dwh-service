package kz.janapost.dwhservice.exception;

/**
 * Единое соответствие «{@code GenericResult.status} → HTTP-код». Тело ответа остаётся {@code GenericResult},
 * HTTP-код дублирует статус, чтобы клиенты (feign-sdk, мобильное приложение, gateway) различали ошибки без разбора тела.
 * <ul>
 *     <li>{@code 0} → 200;</li>
 *     <li>{@code -1}, {@code -2}, {@code -101} (ошибка в запросе) → 400;</li>
 *     <li>{@code -3} (Kafka недоступна, повторить позже) → 503;</li>
 *     <li>{@code -90} (непредвиденная ошибка) → 500;</li>
 *     <li>{@code -NNN}, где NNN — HTTP-код 4xx/5xx ({@code -401}, {@code -413}, {@code -429} …) → NNN;</li>
 *     <li>остальное → 500.</li>
 * </ul>
 * feign-sdk повторяет запрос на 429 и 5xx, поэтому 5xx — только для временных и неожиданных ошибок.
 */
public final class ResultHttpStatus {
    public static final int OK = 0;
    public static final int VALIDATION_ERROR = -1;
    public static final int UNIMPORTANT_ERROR = -2;
    public static final int TEMPORARILY_UNAVAILABLE = -3;
    public static final int UNEXPECTED_ERROR = -90;
    public static final int NOT_READABLE = -101;

    private static final int HTTP_OK = 200;
    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_INTERNAL_ERROR = 500;
    private static final int HTTP_SERVICE_UNAVAILABLE = 503;
    private static final int HTTP_ERROR_FROM = 400;
    private static final int HTTP_ERROR_TO = 599;

    private ResultHttpStatus() {
    }

    public static int httpStatus(int resultStatus) {
        return switch (resultStatus) {
            case OK -> HTTP_OK;
            case VALIDATION_ERROR, UNIMPORTANT_ERROR, NOT_READABLE -> HTTP_BAD_REQUEST;
            case TEMPORARILY_UNAVAILABLE -> HTTP_SERVICE_UNAVAILABLE;
            default -> -resultStatus >= HTTP_ERROR_FROM && -resultStatus <= HTTP_ERROR_TO
                    ? -resultStatus
                    : HTTP_INTERNAL_ERROR;
        };
    }
}
