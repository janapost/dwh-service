package kz.janapost.dwhservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.RetryableException;
import feign.Target;
import kz.janapost.commonsdk.bean.GenericResult;
import kz.janapost.dwhservice.config.interceptor.AdminAuthInterceptor;
import kz.janapost.dwhservice.config.interceptor.CORSInterceptor;
import kz.janapost.dwhservice.config.interceptor.ClientAuthInterceptor;
import kz.janapost.dwhservice.config.properties.MobileIngestProperties;
import kz.janapost.dwhservice.controller.client.v1.ClientLogControllerV1;
import kz.janapost.dwhservice.controller.client.v1.ClientStatControllerV1;
import kz.janapost.dwhservice.controller.pub.v1.PublicLogControllerV1;
import kz.janapost.dwhservice.exception.ResultHttpStatus;
import kz.janapost.dwhservice.exception.TooManyRequestsException;
import kz.janapost.dwhservice.model.vo.IngestResultVO;
import kz.janapost.dwhservice.service.MobileIngestService;
import kz.janapost.dwhservice.util.ClientIpResolver;
import kz.janapost.feignsdk.decoder.GenericResultDecoder;
import kz.janapost.feignsdk.decoder.GenericResultErrorDecoder;
import kz.janapost.feignsdk.exception.FeignCallException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Ответы dwh-service глазами {@code kz.janapost:feign-sdk} ({@code GenericResultFeignConfig}): так их увидит
 * другой сервис, если будет вызывать dwh через {@code @FeignClient}. Ответ берётся из настоящего MockMvc-вызова.
 * <ul>
 *     <li>успех — {@link GenericResultDecoder} возвращает тело;</li>
 *     <li>429 и 503 — {@link RetryableException}, feign повторит запрос;</li>
 *     <li>400 / 413 — {@link FeignCallException} со статусом и сообщением из тела, без повтора.</li>
 * </ul>
 */
@WebMvcTest(controllers = {ClientLogControllerV1.class, ClientStatControllerV1.class, PublicLogControllerV1.class})
@Import({ClientAuthInterceptor.class, AdminAuthInterceptor.class, CORSInterceptor.class, MobileIngestProperties.class,
        ClientIpResolver.class})
class FeignSdkCompatibilityTest {

    private static final String PATH = "/public/v1/log/add-batch";
    private static final String LOG_BODY = "{\"items\":[{\"level\":\"ERROR\",\"message\":\"crash\"}]}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private MobileIngestService mobileIngestService;

    @Test
    @DisplayName("успех — GenericResultDecoder отдаёт тело со status = 0")
    void ok_decoded() throws Exception {
        when(mobileIngestService.acceptPublicLogs(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(GenericResult.ok(new IngestResultVO(1))));

        Response response = asyncCall();

        assertThat(response.status()).isEqualTo(200);
        Object decoded = new GenericResultDecoder(objectMapper).decode(response, GenericResult.class);
        assertThat(((GenericResult<?>) decoded).getStatus()).isZero();
    }

    @Test
    @DisplayName("Kafka недоступна — 503, feign повторит")
    void kafkaUnavailable_retryable() throws Exception {
        when(mobileIngestService.acceptPublicLogs(any(), any())).thenReturn(CompletableFuture.completedFuture(
                new GenericResult<>(ResultHttpStatus.TEMPORARILY_UNAVAILABLE, "Temporarily unavailable, retry later", null)));

        Response response = asyncCall();

        assertThat(response.status()).isEqualTo(503);
        assertThat(errorDecoder().decode("DwhClient#addBatch", response)).isInstanceOf(RetryableException.class);
    }

    @Test
    @DisplayName("лимит частоты — 429 с Retry-After, feign повторит")
    void rateLimited_retryable() throws Exception {
        when(mobileIngestService.acceptPublicLogs(any(), any())).thenThrow(new TooManyRequestsException(30));

        Response response = syncCall(LOG_BODY);

        assertThat(response.status()).isEqualTo(429);
        assertThat(errorDecoder().decode("DwhClient#addBatch", response)).isInstanceOf(RetryableException.class);
    }

    @Test
    @DisplayName("ошибка валидации — 400, FeignCallException со статусом -1 из тела, без повтора")
    void validationError_businessException() throws Exception {
        Response response = syncCall("{\"items\":[{\"level\":\"DEBUG\"}]}");

        assertThat(response.status()).isEqualTo(400);
        Exception decoded = errorDecoder().decode("DwhClient#addBatch", response);
        assertThat(decoded).isExactlyInstanceOf(FeignCallException.class);
        assertThat(((FeignCallException) decoded).status()).isEqualTo(400);
        assertThat(decoded.getMessage()).contains("status=-1");
    }

    @Test
    @DisplayName("тело больше лимита — 413, FeignCallException со статусом -413, без повтора")
    void payloadTooLarge_businessException() throws Exception {
        Response response = syncCall("{\"items\":[{\"level\":\"INFO\",\"message\":\"" + "x".repeat(70_000) + "\"}]}");

        assertThat(response.status()).isEqualTo(413);
        Exception decoded = errorDecoder().decode("DwhClient#addBatch", response);
        assertThat(decoded).isExactlyInstanceOf(FeignCallException.class);
        assertThat(decoded.getMessage()).contains("status=-413");
    }

    private GenericResultErrorDecoder errorDecoder() {
        return new GenericResultErrorDecoder(objectMapper);
    }

    private Response syncCall(String body) throws Exception {
        MvcResult result = mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        return toFeignResponse(result.getResponse());
    }

    private Response asyncCall() throws Exception {
        MvcResult started = mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(LOG_BODY)).andReturn();
        MvcResult result = mockMvc.perform(asyncDispatch(started)).andReturn();
        return toFeignResponse(result.getResponse());
    }

    private static Response toFeignResponse(MockHttpServletResponse servletResponse) {
        Map<String, Collection<String>> headers = new HashMap<>();
        for (String name : servletResponse.getHeaderNames()) {
            headers.put(name, servletResponse.getHeaders(name));
        }
        RequestTemplate template = new RequestTemplate();
        template.feignTarget(new Target.HardCodedTarget<>(DwhClient.class, "dwh-service", "http://dwh-service"));
        Request request = Request.create(Request.HttpMethod.POST, "http://dwh-service" + PATH, Map.of(),
                LOG_BODY.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8, template);
        return Response.builder()
                .status(servletResponse.getStatus())
                .reason("")
                .request(request)
                .headers(headers)
                .body(servletResponse.getContentAsByteArray())
                .build();
    }

    /** Маркер для {@link Target}: feign-sdk берёт из него имя сервиса для сообщений об ошибке. */
    private interface DwhClient {
    }
}
