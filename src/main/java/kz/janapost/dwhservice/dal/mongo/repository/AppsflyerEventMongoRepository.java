package kz.janapost.dwhservice.dal.mongo.repository;

import kz.janapost.dwhservice.model.appsflyer.AppsflyerEventName;
import kz.janapost.dwhservice.model.appsflyer.AppsflyerPlatform;
import kz.janapost.dwhservice.model.po.AppsflyerEventPO;
import kz.janapost.dwhservice.model.po.AppsflyerEventPO.AppsflyerEventStatus;
import kz.janapost.mongodb.AbstractMongoRepository;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

/** Журнал AppsFlyer. Запросы — как в client-service ({@code AppsflyerEventMongoRepository}), коллекция та же. */
@Repository
public class AppsflyerEventMongoRepository extends AbstractMongoRepository<AppsflyerEventPO> {
    /** Окно ретрая и перевода NO_IDENTITY: давние события исказили бы атрибуцию (AppsFlyer засчитает их поздно). */
    static final long RETRY_WINDOW_MS = 24L * 60 * 60 * 1000;
    /** Отправка занимает секунды; PENDING дольше этого — строка, брошенная упавшим подом. */
    static final long STALE_PENDING_MS = 10L * 60 * 1000;
    private static final int BSON_STRING_TYPE = 2;

    public AppsflyerEventMongoRepository(MongoTemplate mongoTemplate) {
        super(mongoTemplate, AppsflyerEventPO.class);
    }

    /** Именно insert, а не save: на дубле {@code dedupKey} нужен {@code DuplicateKeyException}. */
    public AppsflyerEventPO insert(AppsflyerEventPO po) {
        return mongoTemplate.insert(po, getCollectionName());
    }

    /**
     * Строки для ретрая: {@code FAILED} (без ограничения по возрасту, как в client-service) и «зависшие» {@code PENDING}
     * (под умер между захватом и ответом) — только за {@link #RETRY_WINDOW_MS}. Если под умер уже после ответа AppsFlyer,
     * событие уйдёт повторно: дубль редкий, потеря хуже. Перед отправкой строку нужно захватить ({@link #claimForRetry}).
     */
    public List<AppsflyerEventPO> findFailedForRetry(int maxAttempts, int limit) {
        Query query = new Query(retryCriteria(maxAttempts, System.currentTimeMillis()));
        query.limit(limit);
        return findList(query);
    }

    /**
     * Атомарно забирает строку под отправку: {@code PENDING} + {@code claimTime = now}, если она всё ещё подходит
     * ретраю. Второй под (ретрай вышел за блокировку) получит {@code false} и не отправит строку повторно.
     */
    public boolean claimForRetry(String id, int maxAttempts) {
        long now = System.currentTimeMillis();
        Query query = new Query(new Criteria().andOperator(Criteria.where("_id").is(id), retryCriteria(maxAttempts, now)));
        Update update = new Update()
                .set("status", AppsflyerEventStatus.PENDING)
                .set("claimTime", new Date(now))
                .set("updateTime", new Date(now));
        return mongoTemplate.findAndModify(query, update, instance, getCollectionName()) != null;
    }

    /**
     * «Зависший» PENDING — не трогали дольше {@link #STALE_PENDING_MS} с момента захвата ({@code claimTime}).
     * У строк из client-service {@code claimTime} нет — для них, как раньше, считается от {@code createTime}.
     */
    private static Criteria retryCriteria(int maxAttempts, long now) {
        Date staleBefore = new Date(now - STALE_PENDING_MS);
        Criteria stalePending = new Criteria().andOperator(
                Criteria.where("status").is(AppsflyerEventStatus.PENDING),
                Criteria.where("createTime").gte(new Date(now - RETRY_WINDOW_MS)),
                new Criteria().orOperator(
                        Criteria.where("claimTime").lt(staleBefore),
                        new Criteria().andOperator(Criteria.where("claimTime").exists(false),
                                Criteria.where("createTime").lt(staleBefore)))
        );
        return new Criteria().andOperator(
                new Criteria().orOperator(Criteria.where("status").is(AppsflyerEventStatus.FAILED), stalePending),
                Criteria.where("attempt").lt(maxAttempts)
        );
    }

    /** {@code $type: "string"} повторяет фильтр partial-индекса {@code dedupKey}, иначе индекс не используется. */
    public AppsflyerEventPO findOneByDedupKey(String dedupKey) {
        return findOne(new Query(new Criteria().andOperator(
                Criteria.where("dedupKey").is(dedupKey),
                Criteria.where("dedupKey").type(BSON_STRING_TYPE)
        )));
    }

    public void updateAfterSend(String id, AppsflyerEventStatus status, String response, String error) {
        Update update = new Update()
                .set("status", status)
                .set("response", response)
                .set("error", error)
                .set("updateTime", new Date())
                .inc("attempt", 1);
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(id)), update, instance, getCollectionName());
    }

    /**
     * Отправка выключена (теневой режим): строка ждёт включения как {@code FAILED} без попытки. Ретрай подберёт её,
     * когда отправку включат; окна по возрасту у {@code FAILED} нет.
     */
    public void holdUnsent(String id, String reason) {
        Update update = new Update()
                .set("status", AppsflyerEventStatus.FAILED)
                .set("error", reason)
                .set("updateTime", new Date());
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(id)), update, instance, getCollectionName());
    }

    public boolean existsByOrderIdAndEventNameAndStatus(String orderId, AppsflyerEventName eventName,
                                                        AppsflyerEventStatus status) {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("orderId").is(orderId),
                Criteria.where("eventName").is(eventName),
                Criteria.where("status").is(status)
        ));
        return mongoTemplate.exists(query, instance, getCollectionName());
    }

    /** Hint на {@code (status, createTime)}: без индекса запрос падает, а не уходит в полный скан. */
    public List<String> findIdsForCleanup(List<AppsflyerEventStatus> statuses, Date createdBefore, int limit) {
        Query query = new Query(cleanupCriteria(statuses, createdBefore));
        query.fields().include("_id");
        query.withHint(new Document("status", 1).append("createTime", 1));
        query.limit(limit);
        return mongoTemplate.find(query, instance, getCollectionName()).stream()
                .map(AppsflyerEventPO::getId)
                .toList();
    }

    /** Условия повторяются в remove: строка, изменившаяся между find и remove, не удалится. */
    public long deleteByIdsForCleanup(List<String> ids, List<AppsflyerEventStatus> statuses, Date createdBefore) {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("_id").in(ids),
                cleanupCriteria(statuses, createdBefore)
        ));
        return mongoTemplate.remove(query, instance, getCollectionName()).getDeletedCount();
    }

    /**
     * События, ждавшие AppsFlyer ID клиента, отдаются ретраю с уже известным ID. Только свежие
     * ({@link #RETRY_WINDOW_MS}). Повторный вызов безопасен: updateMulti по индексу {@code (jpid, status)}.
     */
    public void promoteNoIdentityToRetry(String jpid, String appsflyerId, AppsflyerPlatform platform) {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("jpid").is(jpid),
                Criteria.where("status").is(AppsflyerEventStatus.NO_IDENTITY),
                Criteria.where("createTime").gte(new Date(System.currentTimeMillis() - RETRY_WINDOW_MS))
        ));
        Update update = new Update()
                .set("appsflyerId", appsflyerId)
                .set("platform", platform)
                .set("status", AppsflyerEventStatus.FAILED)
                .set("attempt", 0)
                .set("updateTime", new Date());
        mongoTemplate.updateMulti(query, update, instance, getCollectionName());
    }

    private static Criteria cleanupCriteria(List<AppsflyerEventStatus> statuses, Date createdBefore) {
        return new Criteria().andOperator(
                Criteria.where("status").in(statuses),
                Criteria.where("createTime").lt(createdBefore)
        );
    }
}
