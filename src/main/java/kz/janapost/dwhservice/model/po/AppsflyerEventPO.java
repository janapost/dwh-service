package kz.janapost.dwhservice.model.po;

import kz.janapost.dwhservice.model.appsflyer.AppsflyerEventName;
import kz.janapost.dwhservice.model.appsflyer.AppsflyerPlatform;
import lombok.Data;
import org.springframework.data.mongodb.core.mapping.MongoId;

import java.util.Date;
import java.util.Map;

/**
 * Журнал S2S-событий AppsFlyer: одна строка — одно бизнес-событие (повторы обновляют её же).
 * Коллекция {@code AppsflyerEventPO} — <b>та же схема, что в client-service</b>: история переносится копированием.
 *
 * <p>{@link #dedupKey} — ключ бизнес-события ({@code <eventName>:<businessId>}). Строка вставляется через insert
 * <b>до</b> отправки, поэтому {@code DuplicateKeyException} значит «событие уже было»: повтор из Kafka или ретрай
 * не дают второй конверсии. У старых строк client-service ключа нет.
 *
 * <p>Индексы создаются вручную ({@code scripts/s2s/s2s-indexes.js}):
 * <ul>
 *     <li>{@code (dedupKey)} unique, partial {@code dedupKey: {$type: "string"}};</li>
 *     <li>{@code (status, attempt)} — ретрай;</li>
 *     <li>{@code (jpid, status)} — NO_IDENTITY в ретрай, когда придёт ID;</li>
 *     <li>{@code (orderId, eventName, status)} — проверка старых строк af_purchase по заказу;</li>
 *     <li>{@code (status, createTime)} — очистка.</li>
 * </ul>
 */
@Data
public class AppsflyerEventPO {
    @MongoId
    private String id;
    private String jpid;
    private String orderId;
    private String appsflyerId;
    private AppsflyerPlatform platform;
    private AppsflyerEventName eventName;
    private String dedupKey;
    private String appId;
    private Map<String, Object> eventParams;
    private AppsflyerEventStatus status;
    private String error;
    private String response;
    /** Время события (уходит в AppsFlyer как {@code eventTime}, в том числе при ретрае). */
    private Date createTime;
    private String source;
    private Integer attempt;
    private Date updateTime;
    /**
     * Когда строку забрали под отправку (резерв или ретрай). PENDING без движения дольше 10 минут после этого —
     * брошен упавшим подом. Поля нет в client-service: там «зависание» считалось от {@code createTime}.
     */
    private Date claimTime;

    public enum AppsflyerEventStatus {
        SENT, FAILED, SKIPPED, DISABLED,
        /** Строка зарезервирована, отправка в процессе. */
        PENDING,
        /** У клиента ещё нет AppsFlyer ID; уйдёт в ретрай, когда ID придёт с устройства. */
        NO_IDENTITY
    }
}
