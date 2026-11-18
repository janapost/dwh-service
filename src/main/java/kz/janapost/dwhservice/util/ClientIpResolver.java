package kz.janapost.dwhservice.util;

import jakarta.servlet.http.HttpServletRequest;
import kz.janapost.dwhservice.config.properties.MobileIngestProperties;
import kz.janapost.simplelogger.SimpleLogger;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * IP клиента для лимитов частоты.
 * <p>
 * Заголовку {@code X-Real-IP} доверяем, <b>только если запрос пришёл с адреса доверенного прокси</b>
 * ({@code mobile.trusted-proxies}, CIDR): тогда заголовок поставил ingress / gateway. Иначе берём адрес соединения:
 * клиент, который обратился к сервису в обход прокси, не может подставить чужой IP и обойти лимит «на IP».
 * По умолчанию список пуст — заголовку не доверяем, пока не указан точный CIDR подов ingress / gateway.
 * <p>
 * Чего код сделать не может: если доверенный прокси <b>пропускает</b> {@code X-Real-IP} от клиента, а не
 * перезаписывает его, подмена остаётся возможной. Это проверяется на окружении до включения
 * {@code DWH_MOBILE_PUBLIC_ENABLED}.
 * <p>
 * В заголовке принимается только IP-литерал. Имя хоста не принимается: {@link InetAddress#getByName} пошёл бы в DNS.
 */
@Component
public class ClientIpResolver {
    public static final String REAL_IP_HEADER = "X-Real-IP";
    private static final String LOG_NAME = "mobile-client-ip[delete10]";

    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");
    private static final int IPV4_BITS = 32;
    private static final int IPV6_BITS = 128;
    private static final int BYTE_BITS = 8;
    private static final int BYTE_MASK = 0xFF;

    private final List<Cidr> trustedProxies;

    public ClientIpResolver(MobileIngestProperties properties) {
        this.trustedProxies = properties.getTrustedProxies().stream()
                .map(String::trim)
                .filter(cidr -> !cidr.isEmpty())
                .map(Cidr::parse)
                .toList();
        if (properties.isPublicEnabled() && trustedProxies.isEmpty()) {
            // Безопасно (заголовку не доверяем), но в k8s все запросы приходят с адреса ingress:
            // лимит «на IP» для /public станет общим для всех клиентов.
            SimpleLogger.getInstanceDayly(LOG_NAME, getClass()).append(
                    "mobile.public-enabled=true, but mobile.trusted-proxies is empty: X-Real-IP is ignored, "
                            + "/public per-IP limit is counted by the proxy address. Set DWH_TRUSTED_PROXIES to the ingress CIDR.");
        }
    }

    public String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!isTrustedProxy(remote)) {
            return remote;
        }
        String header = request.getHeader(REAL_IP_HEADER);
        if (header == null) {
            return remote;
        }
        String ip = header.trim();
        return parseLiteral(ip) != null ? ip : remote;
    }

    boolean isTrustedProxy(String address) {
        InetAddress inet = parseLiteral(address);
        if (inet == null) {
            return false;
        }
        for (Cidr cidr : trustedProxies) {
            if (cidr.contains(inet)) {
                return true;
            }
        }
        return false;
    }

    /** IP-литерал без обращения к DNS; {@code null}, если это не IPv4 / IPv6. */
    static InetAddress parseLiteral(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        boolean v4 = IPV4.matcher(value).matches();
        boolean v6 = !v4 && value.indexOf(':') >= 0 && IPV6.matcher(value).matches();
        if (!v4 && !v6) {
            return null;
        }
        try {
            return InetAddress.getByName(value); // литерал: разбирается без DNS
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** Сеть в нотации CIDR: {@code 10.0.0.0/8}, {@code ::1/128}; без маски — один адрес. */
    record Cidr(byte[] network, int prefix) {

        static Cidr parse(String cidr) {
            int slash = cidr.indexOf('/');
            String address = slash < 0 ? cidr : cidr.substring(0, slash);
            InetAddress inet = parseLiteral(address);
            if (inet == null) {
                throw new IllegalStateException("kz.janapost.dwh-service.mobile.trusted-proxies: not an IP/CIDR: " + cidr);
            }
            int maxBits = inet.getAddress().length == 4 ? IPV4_BITS : IPV6_BITS;
            int prefix = slash < 0 ? maxBits : Integer.parseInt(cidr.substring(slash + 1).trim());
            if (prefix < 0 || prefix > maxBits) {
                throw new IllegalStateException("kz.janapost.dwh-service.mobile.trusted-proxies: bad prefix: " + cidr);
            }
            return new Cidr(inet.getAddress(), prefix);
        }

        boolean contains(InetAddress address) {
            byte[] bytes = address.getAddress();
            if (bytes.length != network.length) {
                return false;
            }
            int fullBytes = prefix / BYTE_BITS;
            for (int i = 0; i < fullBytes; i++) {
                if (bytes[i] != network[i]) {
                    return false;
                }
            }
            int restBits = prefix % BYTE_BITS;
            if (restBits == 0) {
                return true;
            }
            int mask = (BYTE_MASK << (BYTE_BITS - restBits)) & BYTE_MASK;
            return (bytes[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
