package kz.janapost.dwhservice.service;

import kz.janapost.commonsdk.bean.GenericResult;
import kz.janapost.dwhservice.model.fo.client.v1.ClientLogBatchFO;
import kz.janapost.dwhservice.model.fo.client.v1.ClientStatBatchFO;
import kz.janapost.dwhservice.model.fo.pub.v1.PublicLogBatchFO;

import java.util.concurrent.CompletableFuture;

/**
 * Приём логов и статистики мобильного приложения: проверка, лимит частоты, маскирование ПДн,
 * публикация в Kafka. Ответ готов, когда Kafka подтвердила всю пачку (или не смогла).
 * <ul>
 *     <li>{@code ok(accepted = N)} — Kafka приняла все записи;</li>
 *     <li>{@code ok(accepted = 0)} — приём выключен флагом;</li>
 *     <li>{@code -3} — Kafka недоступна, повторить позже с теми же {@code eventId}.</li>
 * </ul>
 */
public interface MobileIngestService {

    /** Логи после входа; {@code jpid} — только из токена. */
    CompletableFuture<GenericResult<?>> acceptClientLogs(String jpid, ClientLogBatchFO fo);

    /** Статистика после входа; {@code jpid} — только из токена. */
    CompletableFuture<GenericResult<?>> acceptClientStats(String jpid, ClientStatBatchFO fo);

    /** Логи до входа; {@code ip} — для лимита частоты. */
    CompletableFuture<GenericResult<?>> acceptPublicLogs(String ip, PublicLogBatchFO fo);
}
