package kz.janapost.dwhservice.config;

import kz.janapost.commonsdk.bean.GenericResult;
import kz.janapost.dwhservice.exception.ResultHttpStatus;
import lombok.NonNull;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Ставит HTTP-код по {@code GenericResult.status} ({@link ResultHttpStatus}) для всех ответов контроллеров
 * и {@code GlobalExceptionHandler}, в том числе асинхронных ({@code CompletableFuture}). Тело не меняется.
 */
@ControllerAdvice
public class GenericResultHttpStatusAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(@NonNull MethodParameter returnType,
                            @NonNull Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, @NonNull MethodParameter returnType, @NonNull MediaType selectedContentType,
                                  @NonNull Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  @NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response) {
        if (body instanceof GenericResult<?> result && result.getStatus() != ResultHttpStatus.OK) {
            response.setStatusCode(HttpStatusCode.valueOf(ResultHttpStatus.httpStatus(result.getStatus())));
        }
        return body;
    }
}
