package kz.janapost.dwhservice.dal.mongo.repository;

import kz.janapost.dwhservice.model.appsflyer.DeepLinkData;
import kz.janapost.dwhservice.model.po.AppsflyerIdentityPO;
import kz.janapost.mongodb.AbstractMongoRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.aggregation.ConditionalOperators;
import org.springframework.data.mongodb.core.aggregation.LiteralOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.Date;

/** AppsFlyer ID устройств. Запросы — как в client-service ({@code AppsflyerIdentityMongoRepository}). */
@Repository
public class AppsflyerIdentityMongoRepository extends AbstractMongoRepository<AppsflyerIdentityPO> {

    public AppsflyerIdentityMongoRepository(MongoTemplate mongoTemplate) {
        super(mongoTemplate, AppsflyerIdentityPO.class);
    }

    public AppsflyerIdentityPO findOneByJpid(String jpid) {
        return findOne(new Query(Criteria.where("_id").is(jpid)));
    }

    /**
     * Атомарный pipeline-upsert (MongoDB 4.2+): вход и sync приходят почти одновременно, полная перезапись документа
     * одним из них затирала бы поля другого.
     * <ul>
     *     <li>{@code appsflyerId} / {@code os} / {@code updateTime} — последнее значение (переустановка, смена устройства);</li>
     *     <li>{@code createTime} / {@code deepLinkData} / {@code userCreateTime} — через {@code $ifNull}: первичная
     *         атрибуция не перезаписывается, но дозаполняется; не пришедшие значения не затирают сохранённые.</li>
     * </ul>
     */
    public void upsertIdentity(String jpid, String appsflyerId, String os, DeepLinkData deepLinkData,
                               Date userCreateTime, Date now) {
        AggregationUpdate update = AggregationUpdate.update()
                .set("appsflyerId").toValue(appsflyerId)
                .set("os").toValue(os)
                .set("updateTime").toValue(now)
                .set("createTime").toValue(ConditionalOperators.ifNull("createTime").then(now));
        if (deepLinkData != null) {
            // $literal: значения атрибуции приходят с устройства, строка вида "$x" иначе читается как ссылка на поле
            Object value = mongoTemplate.getConverter().convertToMongoType(deepLinkData);
            update.set("deepLinkData").toValue(
                    ConditionalOperators.ifNull("deepLinkData").then(LiteralOperators.Literal.asLiteral(value)));
        }
        if (userCreateTime != null) {
            update.set("userCreateTime").toValue(ConditionalOperators.ifNull("userCreateTime").then(userCreateTime));
        }
        mongoTemplate.upsert(new Query(Criteria.where("_id").is(jpid)), update, instance, getCollectionName());
    }
}
