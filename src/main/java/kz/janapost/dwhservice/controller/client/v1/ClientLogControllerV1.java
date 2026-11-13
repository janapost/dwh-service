package kz.janapost.dwhservice.controller.client.v1;

import jakarta.validation.Valid;
import kz.janapost.commonsdk.bean.GenericResult;
import kz.janapost.dwhservice.model.co.AccessTokenPayload;
import kz.janapost.dwhservice.model.fo.client.v1.ClientLogBatchFO;
import kz.janapost.dwhservice.service.MobileIngestService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

/** Логи мобильного приложения после входа → Kafka {@code event_dwh_log} → ClickHouse. */
@RestController
@RequestMapping("/client/v1/log")
@RequiredArgsConstructor
public class ClientLogControllerV1 {

    private final MobileIngestService mobileIngestService;

    @PostMapping("/add-batch")
    public CompletableFuture<GenericResult<?>> addBatch(
            @RequestBody @Valid ClientLogBatchFO fo,
            @RequestAttribute("payload") AccessTokenPayload payload
    ) {
        return mobileIngestService.acceptClientLogs(payload.getJpid(), fo);
    }
}
