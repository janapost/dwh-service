package kz.janapost.dwhservice.dal;

import kz.janapost.dwhservice.model.appsflyer.AppsflyerEventName;
import kz.janapost.dwhservice.model.appsflyer.AppsflyerPlatform;
import kz.janapost.dwhservice.model.dto.AppsflyerEventDTO;
import kz.janapost.dwhservice.model.po.AppsflyerEventPO.AppsflyerEventStatus;

import java.util.Date;
import java.util.List;

/** Журнал S2S-событий AppsFlyer. */
public interface AppsflyerEventDAL {

    /**
     * Резервирует строку бизнес-события.
     *
     * @return {@code false}, если строка с таким {@code dedupKey} уже есть (событие уже было)
     */
    boolean reserve(AppsflyerEventDTO dto);

    AppsflyerEventDTO findOneByDedupKey(String dedupKey);

    List<AppsflyerEventDTO> findFailedForRetry(int maxAttempts, int limit);

    /**
     * Атомарно забирает строку под повторную отправку.
     *
     * @return {@code false}, если строку уже забрал другой под или она больше не подходит ретраю
     */
    boolean claimForRetry(AppsflyerEventDTO dto, int maxAttempts);

    void updateAfterSend(AppsflyerEventDTO dto, AppsflyerEventStatus status, String response, String error);

    void holdUnsent(AppsflyerEventDTO dto, String reason);

    void promoteNoIdentityToRetry(String jpid, String appsflyerId, AppsflyerPlatform platform);

    boolean existsSentByOrderIdAndEventName(String orderId, AppsflyerEventName eventName);

    List<String> findIdsForCleanup(List<AppsflyerEventStatus> statuses, Date createdBefore, int limit);

    long deleteByIdsForCleanup(List<String> ids, List<AppsflyerEventStatus> statuses, Date createdBefore);
}
