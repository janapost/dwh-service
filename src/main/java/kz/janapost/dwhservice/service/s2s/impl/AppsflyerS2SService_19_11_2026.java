package kz.janapost.dwhservice.service.s2s.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import kz.janapost.dwhservice.config.WebAppsflyerConfig;
import kz.janapost.dwhservice.config.properties.AppsflyerProperties;
import kz.janapost.dwhservice.dal.AppsflyerEventDAL;
import kz.janapost.dwhservice.dal.AppsflyerIdentityDAL;
import kz.janapost.dwhservice.model.appsflyer.AppsflyerEventName;
import kz.janapost.dwhservice.model.appsflyer.AppsflyerIdFormat;
import kz.janapost.dwhservice.model.appsflyer.AppsflyerPlatform;
import kz.janapost.dwhservice.model.dto.AppsflyerEventDTO;
import kz.janapost.dwhservice.model.dto.AppsflyerIdentityDTO;
import kz.janapost.dwhservice.model.po.AppsflyerEventPO.AppsflyerEventStatus;
import kz.janapost.dwhservice.model.s2s.S2SEvent;
import kz.janapost.dwhservice.model.s2s.S2SEventType;
import kz.janapost.dwhservice.model.s2s.S2SIdentity;
import kz.janapost.dwhservice.model.s2s.S2SOrder;
import kz.janapost.dwhservice.service.s2s.AbstractS2SService;
import kz.janapost.dwhservice.service.s2s.S2SSendResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * AppsFlyer S2S — перенос {@code AppsflyerS2SServiceImpl} из client-service. <b>Запрос в AppsFlyer тот же байт в байт</b>:
 * тело, имена событий, параметры и {@code eventTime}. Параметры строятся здесь из нейтрального {@link S2SEvent} —
 * ровно так, как их строили триггеры client-service ({@code SyncOrderProcess}, {@code OrderPaymentService_27_10_2024},
 * {@code DeliveredEventSubOrderStatusProcessor}, {@code ClientAuthService_25_09_2024}).
 * <p>
 * Отличия от client-service: таймауты HTTP ({@link WebAppsflyerConfig}), лимиты ретрая из свойств, теневой режим
 * ({@code send-enabled=false}).
 */
@Slf4j
@Service(AppsflyerS2SService_19_11_2026.BEAN_NAME)
public class AppsflyerS2SService_19_11_2026 extends AbstractS2SService<AppsflyerIdentityDTO, AppsflyerEventDTO> {
    public static final String BEAN_NAME = "appsflyer_s2s_sender";

    /** Формат {@code eventTime} в S2S API AppsFlyer — всегда UTC. */
    static final DateTimeFormatter EVENT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
    static final String SOURCE = "S2S_EVENT";
    static final String NO_IDENTITY_ERROR = "AppsFlyer ID is not received from device";
    static final String SEND_DISABLED_ERROR = "AppsFlyer sending is disabled";
    /** Строки, удаляемые очисткой; FAILED / PENDING / NO_IDENTITY ещё нужны ретраю. */
    private static final List<AppsflyerEventStatus> CLEANUP_STATUSES = List.of(AppsflyerEventStatus.SENT, AppsflyerEventStatus.SKIPPED);
    /** Ниже не опускаемся даже по конфигу: строка держит {@code dedupKey}, раньше — риск дублей конверсий. */
    private static final int MIN_RETENTION_DAYS = 30;
    private static final int MAX_CLEANUP_BATCH = 20_000;
    /** Страховка поверх таймаутов клиента: соединение + ответ + запас. */
    private static final Duration BLOCK_MARGIN = Duration.ofSeconds(2);

    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final AppsflyerProperties props;
    private final AppsflyerIdentityDAL identityDAL;
    private final AppsflyerEventDAL eventDAL;

    public AppsflyerS2SService_19_11_2026(ObjectMapper objectMapper,
                                          @Qualifier(WebAppsflyerConfig.APPSFLYER_WEB_CLIENT) WebClient webClient,
                                          AppsflyerProperties props,
                                          AppsflyerIdentityDAL identityDAL,
                                          AppsflyerEventDAL eventDAL) {
        this.objectMapper = objectMapper;
        this.webClient = webClient;
        this.props = props;
        this.identityDAL = identityDAL;
        this.eventDAL = eventDAL;
    }

    /** Отправка с пустым ключом ушла бы в AppsFlyer без авторизации: сервис с такой настройкой не стартует. */
    @PostConstruct
    void validate() {
        if (props.isSendEnabled() && isBlank(props.getS2sKey())) {
            throw new IllegalStateException("kz.janapost.dwh-service.appsflyer.send-enabled=true, but s2s-key is empty");
        }
    }

    @Override
    public String beanName() {
        return BEAN_NAME;
    }

    // --- identity ---

    /**
     * Как {@code saveIdentity} / {@code syncIdentity} в client-service. Перевод NO_IDENTITY в ретрай — всегда, а не
     * только при изменении identity: если прошлый перевод упал, повтор события должен его дотолкнуть (безопасно).
     */
    @Override
    public void saveIdentity(S2SIdentity identity) {
        if (identity == null || isBlank(identity.jpid()) || isBlank(identity.appsflyerId())) {
            return;
        }
        AppsflyerPlatform platform = AppsflyerPlatform.of(identity.deviceOs());
        if (platform == null) {
            return;
        }
        if (!AppsflyerIdFormat.isValid(identity.appsflyerId())) {
            log.warn("[AF] identity skipped, invalid appsflyerId format: jpid={}", identity.jpid());
            return;
        }
        Date userCreateTime = identity.userCreateTime() != null ? new Date(identity.userCreateTime()) : null;
        AppsflyerIdentityDTO current = identityDAL.findOneByJpid(identity.jpid());
        if (current == null || needsUpdate(current, identity.appsflyerId(), platform.name(), identity, userCreateTime)) {
            identityDAL.upsertIdentity(identity.jpid(), identity.appsflyerId(), platform.name(), identity.deepLinkData(),
                    userCreateTime, new Date());
        }
        eventDAL.promoteNoIdentityToRetry(identity.jpid(), identity.appsflyerId(), platform);
    }

    private static boolean needsUpdate(AppsflyerIdentityDTO current, String appsflyerId, String os, S2SIdentity identity,
                                       Date userCreateTime) {
        return !appsflyerId.equals(current.getAppsflyerId())
                || current.getOs() == null
                || !os.equalsIgnoreCase(current.getOs())
                // первичная атрибуция установки не перезаписывается последующими входами
                || (identity.deepLinkData() != null && current.getDeepLinkData() == null)
                || (userCreateTime != null && current.getUserCreateTime() == null);
    }

    @Override
    protected AppsflyerIdentityDTO resolveIdentity(String jpid) {
        AppsflyerIdentityDTO identity = identityDAL.findOneByJpid(jpid);
        if (identity == null || isBlank(identity.getAppsflyerId()) || AppsflyerPlatform.of(identity.getOs()) == null) {
            return null;
        }
        return identity;
    }

    @Override
    protected void promoteIfIdentityAppeared(String jpid) {
        AppsflyerIdentityDTO identity = resolveIdentity(jpid);
        if (identity != null) {
            eventDAL.promoteNoIdentityToRetry(jpid, identity.getAppsflyerId(), AppsflyerPlatform.of(identity.getOs()));
        }
    }

    // --- событие → строка журнала ---

    @Override
    protected boolean supports(S2SEvent event) {
        return true;
    }

    /**
     * af_purchase: заказы, по которым уже есть отправленные старые строки (писались по одному заказу), повторно не
     * шлём — как фильтр {@code existsSentByOrderIdAndEventName} в {@code OrderPaymentService_27_10_2024}.
     */
    @Override
    protected S2SEvent prepare(S2SEvent event) {
        if (event.type() != S2SEventType.PURCHASE) {
            return event;
        }
        List<S2SOrder> orders = event.orders() == null ? List.of() : event.orders().stream()
                .filter(Objects::nonNull)
                .filter(o -> !eventDAL.existsSentByOrderIdAndEventName(o.id(), AppsflyerEventName.AF_PURCHASE))
                .toList();
        if (orders.isEmpty()) {
            return null;
        }
        return new S2SEvent(event.type(), event.jpid(), event.eventTime(), event.orderId(), event.paymentId(), orders,
                event.localAmount(), event.usdValue(), event.marketplace(), event.deliveryDaysTotal());
    }

    @Override
    protected AppsflyerEventDTO newRow(S2SEvent event, AppsflyerIdentityDTO identity) {
        AppsflyerEventName eventName = AppsflyerEventMapping.eventName(event);
        Date occurredAt = event.eventTime() != null ? new Date(event.eventTime()) : new Date();
        AppsflyerEventDTO row = new AppsflyerEventDTO();
        row.setJpid(event.jpid());
        row.setOrderId(AppsflyerEventMapping.orderId(event));
        row.setEventName(eventName);
        row.setEventParams(AppsflyerEventMapping.params(event));
        row.setDedupKey(eventName.value() + ":" + AppsflyerEventMapping.businessId(event));
        row.setCreateTime(occurredAt);
        row.setSource(SOURCE);
        row.setAttempt(0);
        row.setUpdateTime(new Date());
        if (identity == null) {
            row.setStatus(AppsflyerEventStatus.NO_IDENTITY);
            row.setError(NO_IDENTITY_ERROR);
        } else {
            AppsflyerPlatform platform = AppsflyerPlatform.of(identity.getOs());
            row.setAppsflyerId(identity.getAppsflyerId());
            row.setPlatform(platform);
            row.setAppId(resolveAppId(platform));
            row.setStatus(AppsflyerEventStatus.PENDING);
            // захват сразу при резерве: событие из отставания (старый createTime) ретрай не заберёт во время отправки
            row.setClaimTime(new Date());
        }
        return row;
    }

    // --- HTTP ---

    /**
     * Как {@code sendOnce} в client-service: {@code POST /inappevent/{appId}}, JSON. Любая ошибка (сеть, таймаут,
     * не-2xx) — {@code FAILED}, повторит ретрай. Нет app id для платформы — тоже {@code FAILED}.
     */
    @Override
    protected S2SSendResult doSend(AppsflyerEventDTO row) {
        String appId = resolveAppId(row.getPlatform());
        if (isBlank(appId)) {
            return S2SSendResult.failed(new IllegalStateException("AppId is not configured"));
        }
        try {
            String response = webClient.post()
                    .uri("/inappevent/{appId}", appId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody(row))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(props.getConnectTimeout().plus(props.getReadTimeout()).plus(BLOCK_MARGIN));
            return S2SSendResult.sent(response);
        } catch (Exception e) {
            return S2SSendResult.failed(e);
        }
    }

    /** Тело запроса — как {@code buildRequestBody} в client-service. */
    Map<String, Object> requestBody(AppsflyerEventDTO row) {
        Map<String, Object> body = new HashMap<>();
        body.put("appsflyer_id", row.getAppsflyerId());
        body.put("customer_user_id", row.getJpid());
        body.put("eventName", row.getEventName().value());
        Map<String, Object> params = row.getEventParams();
        // AppsFlyer ждёт параметры JSON-строкой
        body.put("eventValue", params == null || params.isEmpty() ? "" : toJsonString(params));
        if (row.getCreateTime() != null) {
            body.put("eventTime", EVENT_TIME_FORMAT.format(row.getCreateTime().toInstant()));
        }
        return body;
    }

    private String toJsonString(Map<String, Object> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    private String resolveAppId(AppsflyerPlatform platform) {
        if (platform == null) {
            return null;
        }
        return switch (platform) {
            case IOS -> props.getIosAppId();
            case ANDROID -> props.getAndroidAppId();
        };
    }

    // --- журнал ---

    @Override
    protected boolean reserve(AppsflyerEventDTO row) {
        boolean reserved = eventDAL.reserve(row);
        if (!reserved) {
            log.info("[AF][EVENT-LOG] duplicate skipped: jpid={}, event={}, dedupKey={}",
                    row.getJpid(), row.getEventName(), row.getDedupKey());
        }
        return reserved;
    }

    @Override
    protected void afterSend(AppsflyerEventDTO row, S2SSendResult result) {
        eventDAL.updateAfterSend(row, result.sent() ? AppsflyerEventStatus.SENT : AppsflyerEventStatus.FAILED,
                result.response(), result.error());
    }

    @Override
    protected void holdUnsent(AppsflyerEventDTO row) {
        eventDAL.holdUnsent(row, SEND_DISABLED_ERROR);
    }

    @Override
    protected List<AppsflyerEventDTO> findForRetry() {
        return eventDAL.findFailedForRetry(props.getRetry().getMaxAttempts(), props.getRetry().getBatchSize());
    }

    @Override
    protected boolean claim(AppsflyerEventDTO row) {
        return eventDAL.claimForRetry(row, props.getRetry().getMaxAttempts());
    }

    /** Строки без jpid или AppsFlyer ID не отправляются (как в {@code AppsflyerRetryScheduler}). */
    @Override
    protected String skipReason(AppsflyerEventDTO row) {
        return row.getJpid() == null || row.getAppsflyerId() == null ? "customer_user_id or appsflyer_id is missing" : null;
    }

    @Override
    protected void markSkipped(AppsflyerEventDTO row, String reason) {
        eventDAL.updateAfterSend(row, AppsflyerEventStatus.SKIPPED, null, reason);
    }

    @Override
    protected boolean sendEnabled() {
        return props.isSendEnabled();
    }

    /**
     * Одна пачка SENT / SKIPPED старше срока хранения (как {@code AppsflyerEventCleanupService_29_10_2026}).
     * {@code deleteMany} без limit, поэтому — find {@code _id} по индексу с limit и remove по ним.
     */
    @Override
    public void cleanup() {
        AppsflyerProperties.EventCleanup cleanup = props.getEventCleanup();
        if (!cleanup.isEnabled()) {
            return;
        }
        int retentionDays = Math.max(cleanup.getRetentionDays(), MIN_RETENTION_DAYS);
        int batchSize = Math.min(Math.max(cleanup.getBatchSize(), 1), MAX_CLEANUP_BATCH);
        Date createdBefore = new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays));
        List<String> ids = eventDAL.findIdsForCleanup(CLEANUP_STATUSES, createdBefore, batchSize);
        if (ids.isEmpty()) {
            return;
        }
        long deleted = eventDAL.deleteByIdsForCleanup(ids, CLEANUP_STATUSES, createdBefore);
        log.info("[AF][CLEANUP] found={}, deleted={}, createdBefore={}", ids.size(), deleted, createdBefore);
    }
}
