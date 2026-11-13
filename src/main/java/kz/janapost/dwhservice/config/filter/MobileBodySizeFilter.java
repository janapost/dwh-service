package kz.janapost.dwhservice.config.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import kz.janapost.commonsdk.bean.GenericResult;
import kz.janapost.dwhservice.config.properties.MobileIngestProperties;
import kz.janapost.dwhservice.exception.PayloadTooLargeException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;

/**
 * Ограничивает размер тела эндпоинтов мобильного приложения до разбора JSON.
 * Есть {@code Content-Length} — сразу {@code -413}. Нет (chunked) — чтение тела обрывается на лимите,
 * {@link PayloadTooLargeException} превращается в {@code -413} в {@code GlobalExceptionHandler}.
 */
@Component
@RequiredArgsConstructor
public class MobileBodySizeFilter extends OncePerRequestFilter {
    static final String CLIENT_LOG_PATH = "/client/v1/log/";
    static final String CLIENT_STAT_PATH = "/client/v1/stat/";
    static final String PUBLIC_LOG_PATH = "/public/v1/log/";

    private final MobileIngestProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return limitFor(path(request)) <= 0;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        long limit = limitFor(path(request));
        long contentLength = request.getContentLengthLong();
        if (contentLength > limit) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), new GenericResult<>(-413,
                    "Request body is larger than " + limit + " bytes, split the batch", null));
            return;
        }
        filterChain.doFilter(contentLength < 0 ? new LimitedBodyRequest(request, limit) : request, response);
    }

    /**
     * Путь так, как его видит Spring при выборе контроллера: без {@code ;параметров}, с декодированным {@code %xx}
     * и без повторных {@code /}. Сырой {@code getRequestURI()} не подходит: {@code /public/v1/log;x/add-batch} или
     * {@code /public/v1/%6cog/add-batch} попали бы в контроллер в обход лимита.
     */
    private static String path(HttpServletRequest request) {
        return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    }

    private long limitFor(String uri) {
        if (uri == null) {
            return 0;
        }
        if (uri.startsWith(PUBLIC_LOG_PATH)) {
            return properties.getPublicMaxBodyBytes();
        }
        if (uri.startsWith(CLIENT_LOG_PATH) || uri.startsWith(CLIENT_STAT_PATH)) {
            return properties.getMaxBodyBytes();
        }
        return 0;
    }

    private static final class LimitedBodyRequest extends HttpServletRequestWrapper {
        private final long limit;

        private LimitedBodyRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new LimitedInputStream(super.getInputStream(), limit);
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long limit;
        private long read;

        private LimitedInputStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = delegate.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws PayloadTooLargeException {
            read += n;
            if (read > limit) {
                throw new PayloadTooLargeException(limit);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
