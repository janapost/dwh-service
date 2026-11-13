package kz.janapost.dwhservice.controller;

import kz.janapost.commonsdk.bean.GenericResult;
import kz.janapost.dwhservice.config.interceptor.AdminAuthInterceptor;
import kz.janapost.dwhservice.config.interceptor.CORSInterceptor;
import kz.janapost.dwhservice.config.interceptor.ClientAuthInterceptor;
import kz.janapost.dwhservice.config.properties.MobileIngestProperties;
import kz.janapost.dwhservice.controller.client.v1.ClientLogControllerV1;
import kz.janapost.dwhservice.controller.client.v1.ClientStatControllerV1;
import kz.janapost.dwhservice.controller.pub.v1.PublicLogControllerV1;
import kz.janapost.dwhservice.exception.TooManyRequestsException;
import kz.janapost.dwhservice.model.vo.IngestResultVO;
import kz.janapost.dwhservice.service.MobileIngestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-слой: маршруты, авторизация, валидация, лимит тела, асинхронный ответ.
 * Логика приёма проверяется в {@code MobileIngestService_13_11_2026Test}, здесь сервис — граница.
 */
@WebMvcTest(controllers = {ClientLogControllerV1.class, ClientStatControllerV1.class, PublicLogControllerV1.class})
@Import({ClientAuthInterceptor.class, AdminAuthInterceptor.class, CORSInterceptor.class, MobileIngestProperties.class})
class MobileIngestControllersTest {

    private static final String TOKEN_INFO = "{\"jpid\":\"JP4595925\",\"type\":\"client\"}";
    private static final String LOG_BODY = """
            {"platform":"android","jpid":"JP_FROM_BODY","items":[{"level":"ERROR","message":"crash"}]}
            """;
    private static final String STAT_BODY = """
            {"platform":"ios","items":[{"metric":"screen_load_ms","value":812}]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MobileIngestService mobileIngestService;

    @Test
    @DisplayName("/client/** без X-TokenInfo — 401, сервис не вызывается")
    void client_withoutToken_unauthorized() throws Exception {
        mockMvc.perform(post("/client/v1/log/add-batch").contentType(MediaType.APPLICATION_JSON).content(LOG_BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(mobileIngestService);
    }

    @Test
    @DisplayName("лог после входа: jpid из токена, а не из тела; ответ асинхронный")
    void clientLog_jpidFromToken() throws Exception {
        when(mobileIngestService.acceptClientLogs(eq("JP4595925"), any())).thenReturn(accepted(1));

        MvcResult result = mockMvc.perform(post("/client/v1/log/add-batch")
                        .header("X-TokenInfo", TOKEN_INFO).contentType(MediaType.APPLICATION_JSON).content(LOG_BODY))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.data.accepted").value(1));
        verify(mobileIngestService).acceptClientLogs(eq("JP4595925"), any());
    }

    @Test
    @DisplayName("статистика после входа принимается")
    void clientStat_accepted() throws Exception {
        when(mobileIngestService.acceptClientStats(eq("JP4595925"), any())).thenReturn(accepted(1));

        MvcResult result = mockMvc.perform(post("/client/v1/stat/add-batch")
                        .header("X-TokenInfo", TOKEN_INFO).contentType(MediaType.APPLICATION_JSON).content(STAT_BODY))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result)).andExpect(jsonPath("$.data.accepted").value(1));
    }

    @Test
    @DisplayName("/public/** без токена проходит, IP берётся из X-Real-IP")
    void public_withoutToken_ipFromHeader() throws Exception {
        when(mobileIngestService.acceptPublicLogs(eq("95.56.12.34"), any())).thenReturn(accepted(1));

        MvcResult result = mockMvc.perform(post("/public/v1/log/add-batch")
                        .header("X-Real-IP", "95.56.12.34").contentType(MediaType.APPLICATION_JSON).content(LOG_BODY))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result)).andExpect(jsonPath("$.status").value(0));
        verify(mobileIngestService).acceptPublicLogs(eq("95.56.12.34"), any());
    }

    @Test
    @DisplayName("невалидное тело — -1 Validation error, сервис не вызывается")
    void invalidBody_validationError() throws Exception {
        mockMvc.perform(post("/public/v1/log/add-batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"level\":\"DEBUG\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(-1));

        verifyNoInteractions(mobileIngestService);
    }

    @Test
    @DisplayName("тело больше лимита (Content-Length) — -413 до разбора JSON")
    void bodyTooLarge_rejectedByFilter() throws Exception {
        String hugeMessage = "x".repeat(70_000);
        String body = "{\"items\":[{\"level\":\"INFO\",\"message\":\"" + hugeMessage + "\"}]}";

        mockMvc.perform(post("/public/v1/log/add-batch").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.status").value(-413));

        verifyNoInteractions(mobileIngestService);
    }

    @Test
    @DisplayName("лимит тела не обходится через ;параметры или %xx в пути (Spring их нормализует при выборе контроллера)")
    void bodyTooLarge_pathTricksDoNotBypassFilter() throws Exception {
        String body = "{\"items\":[{\"level\":\"INFO\",\"message\":\"" + "x".repeat(70_000) + "\"}]}";

        mockMvc.perform(post("/public/v1/log;x=1/add-batch").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.status").value(-413));
        mockMvc.perform(post(URI.create("/public/v1/%6cog/add-batch")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.status").value(-413));
        mockMvc.perform(post("/public//v1/log/add-batch").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.status").value(-413));

        verifyNoInteractions(mobileIngestService);
    }

    @Test
    @DisplayName("превышен лимит частоты — -429 с retryAfterSec")
    void rateLimited_429() throws Exception {
        when(mobileIngestService.acceptPublicLogs(any(), any())).thenThrow(new TooManyRequestsException(30));

        mockMvc.perform(post("/public/v1/log/add-batch").contentType(MediaType.APPLICATION_JSON).content(LOG_BODY))
                .andExpect(jsonPath("$.status").value(-429))
                .andExpect(jsonPath("$.data.retryAfterSec").value(30));
    }

    private static CompletableFuture<GenericResult<?>> accepted(int count) {
        return CompletableFuture.completedFuture(GenericResult.ok(new IngestResultVO(count)));
    }
}
