package kz.janapost.dwhservice.util;

import kz.janapost.dwhservice.config.properties.MobileIngestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientIpResolverTest {

    /** Как задают на окружении: CIDR подов ingress / gateway и localhost. */
    private final ClientIpResolver proxies = resolver(List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "::1/128"));

    @Test
    @DisplayName("по умолчанию trusted-proxies пуст: X-Real-IP не принимается ни от кого, даже от localhost")
    void defaultTrustsNobody() {
        ClientIpResolver defaults = new ClientIpResolver(new MobileIngestProperties());

        assertThat(defaults.clientIp(request("127.0.0.1", "1.2.3.4"))).isEqualTo("127.0.0.1");
        assertThat(defaults.clientIp(request("10.0.0.7", "1.2.3.4"))).isEqualTo("10.0.0.7");
    }

    @Test
    @DisplayName("запрос не через доверенный прокси: X-Real-IP игнорируется, берётся адрес соединения")
    void untrustedRemote_headerIgnored() {
        assertThat(proxies.clientIp(request("203.0.113.5", "1.2.3.4"))).isEqualTo("203.0.113.5");
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.1.2.3", "172.20.0.5", "192.168.1.10", "127.0.0.1", "::1"})
    @DisplayName("запрос через доверенный прокси: X-Real-IP используется")
    void trustedRemote_headerUsed(String proxy) {
        assertThat(proxies.clientIp(request(proxy, "95.56.12.34"))).isEqualTo("95.56.12.34");
    }

    @Test
    @DisplayName("доверенный прокси без X-Real-IP — адрес соединения")
    void trustedRemote_noHeader() {
        assertThat(proxies.clientIp(request("10.0.0.7", null))).isEqualTo("10.0.0.7");
    }

    @ParameterizedTest
    @ValueSource(strings = {"evil.example.com", "1.2.3", "999.1.1.1", "1.2.3.4, 5.6.7.8", "", "localhost"})
    @DisplayName("в X-Real-IP не IP-литерал (имя хоста, мусор, список) — адрес соединения, без обращения к DNS")
    void invalidHeader_remoteUsed(String header) {
        assertThat(proxies.clientIp(request("10.0.0.7", header))).isEqualTo("10.0.0.7");
    }

    @Test
    @DisplayName("IPv6 в заголовке от доверенного прокси принимается")
    void ipv6Header() {
        assertThat(proxies.clientIp(request("10.0.0.7", "2001:db8::1"))).isEqualTo("2001:db8::1");
    }

    @Test
    @DisplayName("граница сети /24: 192.0.2.255 внутри, 192.0.3.0 — нет")
    void cidrBoundary() {
        ClientIpResolver resolver = resolver(List.of("192.0.2.0/24"));

        assertThat(resolver.isTrustedProxy("192.0.2.255")).isTrue();
        assertThat(resolver.isTrustedProxy("192.0.3.0")).isFalse();
        assertThat(resolver.isTrustedProxy("::1")).isFalse();
    }

    @Test
    @DisplayName("маска не кратная 8: 172.16.0.0/12 содержит 172.31.255.255, но не 172.32.0.0")
    void cidrPartialByte() {
        ClientIpResolver resolver = resolver(List.of("172.16.0.0/12"));

        assertThat(resolver.isTrustedProxy("172.31.255.255")).isTrue();
        assertThat(resolver.isTrustedProxy("172.32.0.0")).isFalse();
    }

    @Test
    @DisplayName("пустой список доверенных прокси — заголовку не доверяем никогда")
    void emptyTrustedList() {
        assertThat(resolver(List.of()).clientIp(request("127.0.0.1", "1.2.3.4"))).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("ошибка в trusted-proxies — сервис не стартует")
    void invalidConfig_failsFast() {
        assertThatThrownBy(() -> resolver(List.of("10.0.0.0/33"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> resolver(List.of("proxy.local"))).isInstanceOf(IllegalStateException.class);
    }

    private static ClientIpResolver resolver(List<String> trusted) {
        MobileIngestProperties properties = new MobileIngestProperties();
        properties.setTrustedProxies(trusted);
        return new ClientIpResolver(properties);
    }

    private static MockHttpServletRequest request(String remoteAddr, String realIp) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/public/v1/log/add-batch");
        request.setRemoteAddr(remoteAddr);
        if (realIp != null) {
            request.addHeader(ClientIpResolver.REAL_IP_HEADER, realIp);
        }
        return request;
    }
}
