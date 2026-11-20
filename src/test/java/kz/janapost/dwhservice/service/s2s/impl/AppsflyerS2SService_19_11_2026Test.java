package kz.janapost.dwhservice.service.s2s.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.IndexOptions;
import kz.janapost.dwhservice.config.WebAppsflyerConfig;
import kz.janapost.dwhservice.config.properties.AppsflyerProperties;
import kz.janapost.dwhservice.dal.mongo.AppsflyerEventMongoDAL;
import kz.janapost.dwhservice.dal.mongo.AppsflyerIdentityMongoDAL;
import kz.janapost.dwhservice.dal.mongo.repository.AppsflyerEventMongoRepository;
import kz.janapost.dwhservice.dal.mongo.repository.AppsflyerIdentityMongoRepository;
import kz.janapost.dwhservice.model.appsflyer.AppsflyerEventName;
import kz.janapost.dwhservice.model.appsflyer.AppsflyerPlatform;
import kz.janapost.dwhservice.model.appsflyer.DeepLinkData;
import kz.janapost.dwhservice.model.po.AppsflyerEventPO;
import kz.janapost.dwhservice.model.po.AppsflyerEventPO.AppsflyerEventStatus;
import kz.janapost.dwhservice.model.po.AppsflyerIdentityPO;
import kz.janapost.dwhservice.model.s2s.S2SEvent;
import kz.janapost.dwhservice.model.s2s.S2SEventType;
import kz.janapost.dwhservice.model.s2s.S2SIdentity;
import kz.janapost.dwhservice.model.s2s.S2SOrder;
import kz.janapost.dwhservice.testsupport.DockerApiVersionExtension;
import kz.janapost.dwhservice.testsupport.FakeHttpServer;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AppsFlyer S2S целиком: настоящая Mongo (Testcontainers) и локальный HTTP-сервер вместо AppsFlyer.
 * Главная проверка — запрос в AppsFlyer совпадает с тем, что строит client-service ({@link ClientServiceAppsflyerReference}).
 * Требуется запущенный Docker; без него класс пропускается.
 */
@ExtendWith(DockerApiVersionExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class AppsflyerS2SService_19_11_2026Test {

    private static final String JPID = "JP4595925";
    private static final String AF_ID = "1712345678901-1234567";
    private static final String S2S_KEY = "test-s2s-key";
    private static final String ANDROID_APP = "kz.janapost.app";
    private static final String IOS_APP = "id000000001";
    /** Час назад: внутри окна ретрая и перевода NO_IDENTITY (24 ч от времени события, как в client-service). */
    private static final long EVENT_TIME = System.currentTimeMillis() - 3_600_000L;
    private static final String EVENT_TIME_TEXT =
            AppsflyerS2SService_19_11_2026.EVENT_TIME_FORMAT.format(java.time.Instant.ofEpochMilli(EVENT_TIME));

    @Container
    @SuppressWarnings("resource")
    static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:6.0"));

    private static MongoClient mongoClient;
    private static FakeHttpServer appsflyer;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MongoTemplate mongo;
    private AppsflyerProperties props;
    private AppsflyerS2SService_19_11_2026 service;

    @BeforeAll
    static void start() throws Exception {
        mongoClient = MongoClients.create(MONGO.getConnectionString());
        appsflyer = new FakeHttpServer();
    }

    @AfterAll
    static void stop() {
        appsflyer.close();
        mongoClient.close();
    }

    @BeforeEach
    void setUp() {
        mongo = new MongoTemplate(mongoClient, "dwh-service");
        mongo.getDb().drop();
        // как scripts/s2s/s2s-indexes.js: без уникального индекса дедупликации нет
        mongo.getCollection("AppsflyerEventPO").createIndex(new Document("dedupKey", 1),
                new IndexOptions().unique(true).partialFilterExpression(new Document("dedupKey", new Document("$type", "string"))));
        appsflyer.reset();

        props = new AppsflyerProperties();
        props.setBaseUrl(appsflyer.baseUrl());
        props.setS2sKey(S2S_KEY);
        props.setAndroidAppId(ANDROID_APP);
        props.setIosAppId(IOS_APP);
        props.setSendEnabled(true);
        props.setReadTimeout(Duration.ofMillis(500));
        service = newService();
    }

    private AppsflyerS2SService_19_11_2026 newService() {
        return new AppsflyerS2SService_19_11_2026(objectMapper,
                new WebAppsflyerConfig().appsflyerWebClient(props), props,
                new AppsflyerIdentityMongoDAL(new AppsflyerIdentityMongoRepository(mongo)),
                new AppsflyerEventMongoDAL(new AppsflyerEventMongoRepository(mongo)));
    }

    @Test
    @DisplayName("5 событий: путь, заголовки, тело и строка eventValue — как в client-service")
    void requestsMatchClientService() throws Exception {
        service.saveIdentity(identity("android"));
        List<Object[]> paidOrders = List.of(new Object[]{"O-2", 25_000L}, new Object[]{"O-3", null});

        service.send(event(S2SEventType.COMPLETE_REGISTRATION, null, null, null, null, null, null, null));
        service.send(event(S2SEventType.ORDER_CREATED, "O-1", null, null, 1_500_000L, null, null, null));
        service.send(event(S2SEventType.FIRST_ORDER, "O-1", null, null, null, 31_000L, "wildberries", null));
        service.send(event(S2SEventType.PURCHASE, null, "PAY-1",
                List.of(new S2SOrder("O-2", 25_000L), new S2SOrder("O-3", null)), null, null, null, null));
        service.send(event(S2SEventType.RECEIVED_THE_GOODS, "O-1", null, null, null, null, null, 6L));

        assertThat(appsflyer.requests()).hasSize(5);
        assertRequest(0, AppsflyerEventName.AF_COMPLETE_REGISTRATION, ClientServiceAppsflyerReference.registration());
        // marketplace = null уходит ключом с null — как в client-service
        assertRequest(1, AppsflyerEventName.ORDER_CREATED, ClientServiceAppsflyerReference.orderCreated("O-1", 1_500_000L, null));
        assertRequest(2, AppsflyerEventName.FIRST_ORDER, ClientServiceAppsflyerReference.firstOrder("O-1", 31_000L, "wildberries"));
        assertRequest(3, AppsflyerEventName.AF_PURCHASE, ClientServiceAppsflyerReference.purchase(paidOrders));
        assertRequest(4, AppsflyerEventName.RECEIVED_THE_GOODS, ClientServiceAppsflyerReference.received("O-1", 6L));
    }

    @Test
    @DisplayName("first_order без цены и без маркетплейса; received без дней — те же пропуски параметров, что в client-service")
    void optionalParamsSkippedLikeClientService() throws Exception {
        service.saveIdentity(identity("ios"));

        service.send(event(S2SEventType.FIRST_ORDER, "O-9", null, null, null, null, null, null));
        service.send(event(S2SEventType.RECEIVED_THE_GOODS, "O-9", null, null, null, null, null, null));

        assertRequest(0, AppsflyerEventName.FIRST_ORDER, ClientServiceAppsflyerReference.firstOrder("O-9", null, null));
        assertRequest(1, AppsflyerEventName.RECEIVED_THE_GOODS, ClientServiceAppsflyerReference.received("O-9", null));
        assertThat(appsflyer.requests().get(0).path()).isEqualTo("/inappevent/" + IOS_APP);
    }

    @Test
    @DisplayName("повтор события (Kafka, колбэк) — в AppsFlyer уходит один раз")
    void duplicateEventSentOnce() {
        service.saveIdentity(identity("android"));
        S2SEvent orderCreated = event(S2SEventType.ORDER_CREATED, "O-1", null, null, 100L, null, "ozon", null);

        service.send(orderCreated);
        service.send(orderCreated);

        assertThat(appsflyer.requests()).hasSize(1);
        assertThat(row("order_created:O-1").getStatus()).isEqualTo(AppsflyerEventStatus.SENT);
    }

    @Test
    @DisplayName("нет AppsFlyer ID: NO_IDENTITY без запроса; ID пришёл — ретрай отправляет с исходным временем события")
    void noIdentityThenRetry() throws Exception {
        service.send(event(S2SEventType.COMPLETE_REGISTRATION, null, null, null, null, null, null, null));

        AppsflyerEventPO waiting = row("af_complete_registration:" + JPID);
        assertThat(waiting.getStatus()).isEqualTo(AppsflyerEventStatus.NO_IDENTITY);
        assertThat(appsflyer.requests()).isEmpty();

        service.saveIdentity(identity("android"));
        AppsflyerEventPO promoted = row("af_complete_registration:" + JPID);
        assertThat(promoted.getStatus()).isEqualTo(AppsflyerEventStatus.FAILED);
        assertThat(promoted.getAttempt()).isZero();
        assertThat(promoted.getAppsflyerId()).isEqualTo(AF_ID);
        assertThat(promoted.getPlatform()).isEqualTo(AppsflyerPlatform.ANDROID);

        service.retryPending();

        assertThat(appsflyer.requests()).hasSize(1);
        assertThat(body(0).get("eventTime").asText()).isEqualTo(EVENT_TIME_TEXT);
        assertThat(row("af_complete_registration:" + JPID).getStatus()).isEqualTo(AppsflyerEventStatus.SENT);
    }

    @Test
    @DisplayName("теневой режим: строка ждёт без попытки и без запроса; после включения уходит ретраем")
    void shadowModeHoldsThenSends() {
        props.setSendEnabled(false);
        service.saveIdentity(identity("android"));

        service.send(event(S2SEventType.ORDER_CREATED, "O-1", null, null, 100L, null, "ozon", null));
        service.retryPending();

        AppsflyerEventPO held = row("order_created:O-1");
        assertThat(held.getStatus()).isEqualTo(AppsflyerEventStatus.FAILED);
        assertThat(held.getAttempt()).isZero();
        assertThat(held.getError()).isEqualTo(AppsflyerS2SService_19_11_2026.SEND_DISABLED_ERROR);
        assertThat(appsflyer.requests()).isEmpty();

        props.setSendEnabled(true);
        service.retryPending();

        assertThat(appsflyer.requests()).hasSize(1);
        assertThat(row("order_created:O-1").getStatus()).isEqualTo(AppsflyerEventStatus.SENT);
    }

    @Test
    @DisplayName("af_purchase: заказ со старой отправленной строкой исключается, ключ — по ID оплаты")
    void purchaseSkipsLegacySentOrders() throws Exception {
        service.saveIdentity(identity("android"));
        AppsflyerEventPO legacy = new AppsflyerEventPO();
        legacy.setOrderId("O-OLD");
        legacy.setEventName(AppsflyerEventName.AF_PURCHASE);
        legacy.setStatus(AppsflyerEventStatus.SENT);
        mongo.insert(legacy, "AppsflyerEventPO");

        service.send(event(S2SEventType.PURCHASE, null, "PAY-7",
                List.of(new S2SOrder("O-OLD", 10_000L), new S2SOrder("O-NEW", 20_000L)), null, null, null, null));

        List<Object[]> notSent = new ArrayList<>();
        notSent.add(new Object[]{"O-NEW", 20_000L});
        assertRequest(0, AppsflyerEventName.AF_PURCHASE, ClientServiceAppsflyerReference.purchase(notSent));
        assertThat(row("af_purchase:PAY-7")).isNotNull();
    }

    @Test
    @DisplayName("af_purchase: все заказы уже отправлены по-старому — события нет")
    void purchaseAllLegacySentIsSkipped() {
        service.saveIdentity(identity("android"));
        AppsflyerEventPO legacy = new AppsflyerEventPO();
        legacy.setOrderId("O-OLD");
        legacy.setEventName(AppsflyerEventName.AF_PURCHASE);
        legacy.setStatus(AppsflyerEventStatus.SENT);
        mongo.insert(legacy, "AppsflyerEventPO");

        service.send(event(S2SEventType.PURCHASE, null, "PAY-8", List.of(new S2SOrder("O-OLD", 10_000L)),
                null, null, null, null));

        assertThat(appsflyer.requests()).isEmpty();
        assertThat(row("af_purchase:PAY-8")).isNull();
    }

    @Test
    @DisplayName("af_purchase без ID оплаты — ключ из ID заказов по возрастанию")
    void purchaseWithoutPaymentIdUsesSortedOrderIds() {
        service.saveIdentity(identity("android"));

        service.send(event(S2SEventType.PURCHASE, null, null,
                List.of(new S2SOrder("O-2", 1L), new S2SOrder("O-1", 1L)), null, null, null, null));

        assertThat(row("af_purchase:O-1,O-2")).isNotNull();
    }

    @Test
    @DisplayName("AppsFlyer ответил 500 — FAILED с ошибкой и попыткой; ретрай отправляет повторно")
    void serverErrorIsRetried() {
        service.saveIdentity(identity("android"));
        appsflyer.respond(500, "boom");

        service.send(event(S2SEventType.ORDER_CREATED, "O-1", null, null, 100L, null, "ozon", null));

        AppsflyerEventPO failed = row("order_created:O-1");
        assertThat(failed.getStatus()).isEqualTo(AppsflyerEventStatus.FAILED);
        assertThat(failed.getAttempt()).isEqualTo(1);
        assertThat(failed.getError()).contains("500");

        appsflyer.respond(200, "ok");
        service.retryPending();

        assertThat(appsflyer.requests()).hasSize(2);
        assertThat(row("order_created:O-1").getStatus()).isEqualTo(AppsflyerEventStatus.SENT);
    }

    @Test
    @DisplayName("AppsFlyer завис — FAILED по таймауту ответа, поток не ждёт дольше read-timeout")
    void hangingServerTimesOut() {
        service.saveIdentity(identity("android"));
        appsflyer.delay(3_000);

        long start = System.nanoTime();
        service.send(event(S2SEventType.ORDER_CREATED, "O-1", null, null, 100L, null, "ozon", null));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(2_500);
        assertThat(row("order_created:O-1").getStatus()).isEqualTo(AppsflyerEventStatus.FAILED);
    }

    @Test
    @DisplayName("identity: первичная атрибуция и время регистрации не перезаписываются, AppsFlyer ID — обновляется")
    void identityKeepsFirstAttribution() {
        DeepLinkData first = new DeepLinkData();
        first.setMediaSource("google");
        service.saveIdentity(new S2SIdentity(JPID, AF_ID, "android", first, 1_000L));

        DeepLinkData second = new DeepLinkData();
        second.setMediaSource("tiktok");
        service.saveIdentity(new S2SIdentity(JPID, "1712345678901-7654321", "ios", second, 2_000L));

        AppsflyerIdentityPO saved = mongo.findById(JPID, AppsflyerIdentityPO.class, "AppsflyerIdentityPO");
        assertThat(saved.getAppsflyerId()).isEqualTo("1712345678901-7654321");
        assertThat(saved.getOs()).isEqualTo("IOS");
        assertThat(saved.getDeepLinkData().getMediaSource()).isEqualTo("google");
        assertThat(saved.getUserCreateTime()).isEqualTo(new Date(1_000L));
    }

    @Test
    @DisplayName("identity с неверным форматом ID или платформой не сохраняется")
    void invalidIdentityIgnored() {
        service.saveIdentity(new S2SIdentity(JPID, "not-an-af-id", "android", null, null));
        service.saveIdentity(new S2SIdentity(JPID, AF_ID, "windows", null, null));

        assertThat(mongo.findById(JPID, AppsflyerIdentityPO.class, "AppsflyerIdentityPO")).isNull();
    }

    @Test
    @DisplayName("ретрай: строка без AppsFlyer ID — SKIPPED, запроса нет")
    void retrySkipsRowsWithoutIds() {
        AppsflyerEventPO broken = new AppsflyerEventPO();
        broken.setJpid(JPID);
        broken.setEventName(AppsflyerEventName.ORDER_CREATED);
        broken.setStatus(AppsflyerEventStatus.FAILED);
        broken.setAttempt(0);
        broken.setDedupKey("order_created:O-X");
        broken.setCreateTime(new Date());
        mongo.insert(broken, "AppsflyerEventPO");

        service.retryPending();

        assertThat(appsflyer.requests()).isEmpty();
        assertThat(row("order_created:O-X").getStatus()).isEqualTo(AppsflyerEventStatus.SKIPPED);
    }

    @Test
    @DisplayName("send-enabled=true с пустым ключом — сервис не стартует")
    void sendEnabledRequiresKey() {
        props.setS2sKey("");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> newService().validate())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("захват строки ретрая атомарный: второй под (ретрай вышел за блокировку) её не получает")
    void claimIsExclusive() {
        AppsflyerEventMongoRepository repository = new AppsflyerEventMongoRepository(mongo);
        String id = failedRow("order_created:O-1", new Date(), null).getId();

        assertThat(repository.claimForRetry(id, 3)).isTrue();
        assertThat(repository.claimForRetry(id, 3)).isFalse();
        assertThat(row("order_created:O-1").getStatus()).isEqualTo(AppsflyerEventStatus.PENDING);
    }

    @Test
    @DisplayName("ретрай не отправляет строку, которую уже забрал другой под")
    void retrySkipsRowClaimedByAnotherPod() {
        AppsflyerEventMongoRepository repository = new AppsflyerEventMongoRepository(mongo);
        String id = failedRow("order_created:O-1", new Date(), null).getId();
        // другой под успел забрать строку между нашим поиском и захватом
        List<AppsflyerEventPO> found = repository.findFailedForRetry(3, 50);
        repository.claimForRetry(id, 3);

        service.retryPending();

        assertThat(found).hasSize(1);
        assertThat(appsflyer.requests()).isEmpty();
    }

    @Test
    @DisplayName("PENDING: зависшим считается по времени захвата — свежий захват не трогаем, старый (> 10 мин) отправляем")
    void stalePendingByClaimTime() {
        // событие из отставания: createTime давний, но строку только что забрал консьюмер
        AppsflyerEventPO inFlight = failedRow("order_created:O-1", new Date(System.currentTimeMillis() - 3_600_000L),
                new Date());
        inFlight.setStatus(AppsflyerEventStatus.PENDING);
        mongo.save(inFlight, "AppsflyerEventPO");
        AppsflyerEventPO abandoned = failedRow("order_created:O-2", new Date(System.currentTimeMillis() - 3_600_000L),
                new Date(System.currentTimeMillis() - 11 * 60_000L));
        abandoned.setStatus(AppsflyerEventStatus.PENDING);
        mongo.save(abandoned, "AppsflyerEventPO");

        service.retryPending();

        assertThat(appsflyer.requests()).hasSize(1);
        assertThat(row("order_created:O-1").getStatus()).isEqualTo(AppsflyerEventStatus.PENDING);
        assertThat(row("order_created:O-2").getStatus()).isEqualTo(AppsflyerEventStatus.SENT);
    }

    @Test
    @DisplayName("FAILED без ограничения по возрасту: строка недельной давности уходит ретраем, как в client-service")
    void failedRowsHaveNoAgeWindow() {
        failedRow("order_created:O-1", new Date(System.currentTimeMillis() - 7L * 24 * 3_600_000L), null);

        service.retryPending();

        assertThat(appsflyer.requests()).hasSize(1);
    }

    // --- helpers ---

    /** Строка журнала, готовая к отправке: AppsFlyer ID и платформа есть. */
    private AppsflyerEventPO failedRow(String dedupKey, Date createTime, Date claimTime) {
        AppsflyerEventPO po = new AppsflyerEventPO();
        po.setJpid(JPID);
        po.setAppsflyerId(AF_ID);
        po.setPlatform(AppsflyerPlatform.ANDROID);
        po.setEventName(AppsflyerEventName.ORDER_CREATED);
        po.setEventParams(Map.of("order_id", "x"));
        po.setDedupKey(dedupKey);
        po.setStatus(AppsflyerEventStatus.FAILED);
        po.setAttempt(0);
        po.setCreateTime(createTime);
        po.setClaimTime(claimTime);
        return mongo.insert(po, "AppsflyerEventPO");
    }

    private void assertRequest(int index, AppsflyerEventName eventName, Map<String, Object> expectedParams) throws Exception {
        FakeHttpServer.Request request = appsflyer.requests().get(index);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.header("authentication")).isEqualTo(S2S_KEY);
        assertThat(request.header("Content-Type")).startsWith("application/json");
        JsonNode body = objectMapper.readTree(request.body());
        assertThat(body.get("appsflyer_id").asText()).isEqualTo(AF_ID);
        assertThat(body.get("customer_user_id").asText()).isEqualTo(JPID);
        assertThat(body.get("eventName").asText()).isEqualTo(eventName.value());
        // строка параметров — ровно как ObjectMapper.writeValueAsString в client-service (тот же порядок ключей)
        assertThat(body.get("eventValue").asText()).isEqualTo(objectMapper.writeValueAsString(expectedParams));
        assertThat(body.get("eventTime").asText()).isEqualTo(EVENT_TIME_TEXT);
        assertThat(body.size()).isEqualTo(5);
    }

    private JsonNode body(int index) throws Exception {
        return objectMapper.readTree(appsflyer.requests().get(index).body());
    }

    private AppsflyerEventPO row(String dedupKey) {
        return mongo.findOne(new Query(Criteria.where("dedupKey").is(dedupKey)), AppsflyerEventPO.class, "AppsflyerEventPO");
    }

    private static S2SIdentity identity(String deviceOs) {
        return new S2SIdentity(JPID, AF_ID, deviceOs, null, null);
    }

    private static S2SEvent event(S2SEventType type, String orderId, String paymentId, List<S2SOrder> orders,
                                  Long localAmount, Long usdValue, String marketplace, Long deliveryDays) {
        return new S2SEvent(type, JPID, EVENT_TIME, orderId, paymentId, orders, localAmount, usdValue, marketplace, deliveryDays);
    }
}
