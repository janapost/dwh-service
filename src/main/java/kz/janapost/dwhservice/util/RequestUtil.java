package kz.janapost.dwhservice.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * IP клиента для лимитов частоты: заголовок {@code X-Real-IP}, как в {@code LoginBlockedIpFilter} client-service.
 * <p>
 * Код подмену не предотвращает. Лимит «на IP» работает, только если gateway / ingress <b>сам ставит</b>
 * {@code X-Real-IP} и <b>перезаписывает</b> значение, пришедшее от клиента. Если заголовка нет, берётся адрес
 * соединения — обычно это адрес gateway, и лимит становится общим для всех. Проверить на окружении (ревью, п. 1.3).
 */
public final class RequestUtil {
    public static final String REAL_IP_HEADER = "X-Real-IP";

    private RequestUtil() {
    }

    public static String clientIp(HttpServletRequest request) {
        String ip = request.getHeader(REAL_IP_HEADER);
        if (ip == null || ip.isBlank()) {
            return request.getRemoteAddr();
        }
        return ip.trim();
    }
}
