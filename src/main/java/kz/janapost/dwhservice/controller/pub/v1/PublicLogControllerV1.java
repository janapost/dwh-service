package kz.janapost.dwhservice.controller.pub.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kz.janapost.commonsdk.bean.GenericResult;
import kz.janapost.dwhservice.model.fo.pub.v1.PublicLogBatchFO;
import kz.janapost.dwhservice.service.MobileIngestService;
import kz.janapost.dwhservice.util.RequestUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

/**
 * Логи мобильного приложения до входа (падение на старте, экран входа) → Kafka {@code event_dwh_log}.
 * Без токена: лимит частоты по IP и {@code deviceId}, меньше пачка и тело, отдельный флаг {@code mobile.public-enabled}.
 */
@RestController
@RequestMapping("/public/v1/log")
@RequiredArgsConstructor
public class PublicLogControllerV1 {

    private final MobileIngestService mobileIngestService;

    @PostMapping("/add-batch")
    public CompletableFuture<GenericResult<?>> addBatch(@RequestBody @Valid PublicLogBatchFO fo, HttpServletRequest request) {
        return mobileIngestService.acceptPublicLogs(RequestUtil.clientIp(request), fo);
    }
}
