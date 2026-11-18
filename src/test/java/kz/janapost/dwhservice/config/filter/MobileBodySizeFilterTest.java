package kz.janapost.dwhservice.config.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.janapost.dwhservice.config.properties.MobileIngestProperties;
import kz.janapost.dwhservice.exception.PayloadTooLargeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ветка без {@code Content-Length} (chunked): MockMvc всегда выставляет длину, поэтому фильтр вызывается напрямую.
 * Превышение превращается в {@code -413} в {@code GlobalExceptionHandler} (по причине в цепочке исключений).
 */
class MobileBodySizeFilterTest {

    private final MobileIngestProperties properties = new MobileIngestProperties();
    private final MobileBodySizeFilter filter = new MobileBodySizeFilter(properties, new ObjectMapper());

    @Test
    @DisplayName("chunked-тело больше лимита: чтение обрывается PayloadTooLargeException")
    void chunkedBodyOverLimit_readFails() {
        properties.setPublicMaxBodyBytes(100);
        MockHttpServletRequest request = chunked("/public/v1/log/add-batch", "x".repeat(101));

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> req.getInputStream().readAllBytes()))
                .isInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    @DisplayName("chunked-тело в пределах лимита читается целиком")
    void chunkedBodyWithinLimit_readsAll() throws Exception {
        properties.setPublicMaxBodyBytes(100);
        MockHttpServletRequest request = chunked("/public/v1/log/add-batch", "x".repeat(100));
        byte[][] read = new byte[1][];

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> read[0] = req.getInputStream().readAllBytes());

        assertThat(read[0]).hasSize(100);
    }

    @Test
    @DisplayName("другие пути фильтр не ограничивает")
    void otherPathsAreNotLimited() throws Exception {
        properties.setMaxBodyBytes(10);
        properties.setPublicMaxBodyBytes(10);
        MockHttpServletRequest request = chunked("/logmanager/list", "x".repeat(1000));
        byte[][] read = new byte[1][];

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> read[0] = req.getInputStream().readAllBytes());

        assertThat(read[0]).hasSize(1000);
    }

    private static MockHttpServletRequest chunked(String uri, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
