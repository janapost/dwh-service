package kz.janapost.dwhservice.controller.client.v1;

import jakarta.validation.Valid;
import kz.janapost.commonsdk.bean.GenericResult;
import kz.janapost.dwhservice.model.co.AccessTokenPayload;
import kz.janapost.dwhservice.model.fo.client.v1.ClientStatBatchFO;
import kz.janapost.dwhservice.service.MobileIngestService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

/** Статистика мобильного приложения после входа → Kafka {@code event_dwh_stat} → ClickHouse. */
@RestController
@RequestMapping("/client/v1/stat")
@RequiredArgsConstructor
public class ClientStatControllerV1 {

    private final MobileIngestService mobileIngestService;

    @PostMapping("/add-batch")
    public CompletableFuture<GenericResult<?>> addBatch(
            @RequestBody @Valid ClientStatBatchFO fo,
            @RequestAttribute("payload") AccessTokenPayload payload
    ) {
        return mobileIngestService.acceptClientStats(payload.getJpid(), fo);
    }
}
