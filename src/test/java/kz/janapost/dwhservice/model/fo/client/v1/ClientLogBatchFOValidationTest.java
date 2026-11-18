package kz.janapost.dwhservice.model.fo.client.v1;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import kz.janapost.dwhservice.model.fo.pub.v1.PublicLogBatchFO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Обязательны: platform, appVersion, clientId у пачки; eventId и level / message (логи) или metric / value (статистика)
 * у записи. Остальное проверяется, только если пришло.
 */
class ClientLogBatchFOValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void init() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    @Test
    @DisplayName("минимальная пачка: platform, appVersion, clientId, у записи eventId, level и message — валидна")
    void minimalBatchIsValid() {
        assertThat(validator.validate(batch(item("INFO", "started")))).isEmpty();
    }

    @Test
    @DisplayName("level в любом регистре, но только INFO / WARN / ERROR")
    void level() {
        assertThat(validator.validate(batch(item("warn", "x")))).isEmpty();
        assertThat(paths(batch(item("DEBUG", "x")))).containsExactly("items[0].level");
    }

    @Test
    @DisplayName("без level или message — ошибка")
    void requiredFields() {
        assertThat(paths(batch(item(null, null)))).containsExactlyInAnyOrder("items[0].level", "items[0].message");
    }

    @Test
    @DisplayName("пустой items — ошибка")
    void emptyItems() {
        ClientLogBatchFO fo = batch();
        fo.setItems(List.of());

        assertThat(paths(fo)).containsExactly("items");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "not-a-uuid")
    @DisplayName("eventId обязателен и только UUID")
    void eventId(String eventId) {
        ClientLogItemFO item = item("INFO", "x");
        item.setEventId(eventId);

        assertThat(paths(batch(item))).containsExactly("items[0].eventId");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "web")
    @DisplayName("platform обязательна и только ios / android")
    void platformRequired(String platform) {
        ClientLogBatchFO fo = batch(item("INFO", "x"));
        fo.setPlatform(platform);

        assertThat(paths(fo)).containsExactly("platform");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ios", "Android", "IOS"})
    @DisplayName("platform в любом регистре")
    void platformCaseInsensitive(String platform) {
        ClientLogBatchFO fo = batch(item("INFO", "x"));
        fo.setPlatform(platform);

        assertThat(validator.validate(fo)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    @DisplayName("appVersion обязательна")
    void appVersionRequired(String appVersion) {
        ClientLogBatchFO fo = batch(item("INFO", "x"));
        fo.setAppVersion(appVersion);

        assertThat(paths(fo)).containsExactly("appVersion");
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.12.0", "5.12.0+345", "5.12.0-beta"})
    @DisplayName("appVersion без формата: суффиксы сборок проходят")
    void appVersionFormatIsFree(String appVersion) {
        ClientLogBatchFO fo = batch(item("INFO", "x"));
        fo.setAppVersion(appVersion);

        assertThat(validator.validate(fo)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"install 42", "{id}", "id*", "dwh-mobile-rl:global\n"})
    @DisplayName("clientId обязателен, только [A-Za-z0-9._:-] — значение идёт в ключ Redis")
    void clientIdInvalid(String clientId) {
        ClientLogBatchFO fo = batch(item("INFO", "x"));
        fo.setClientId(clientId);

        assertThat(paths(fo)).containsExactly("clientId");
    }

    @Test
    @DisplayName("clientId: 64 символа — можно, 65 — нет")
    void clientIdLength() {
        ClientLogBatchFO fo = batch(item("INFO", "x"));
        fo.setClientId("c".repeat(64));
        assertThat(validator.validate(fo)).isEmpty();

        fo.setClientId("c".repeat(65));
        assertThat(paths(fo)).containsExactly("clientId");
    }

    @Test
    @DisplayName("лимиты длины: message 4000, attributes — 30 ключей, значение 512")
    void lengths() {
        ClientLogItemFO item = item("INFO", "x".repeat(4001));
        Map<String, String> attributes = new HashMap<>();
        for (int i = 0; i < 31; i++) {
            attributes.put("k" + i, "v");
        }
        attributes.put("long", "v".repeat(513));
        item.setAttributes(attributes);

        assertThat(paths(batch(item))).contains("items[0].message", "items[0].attributes");
    }

    @Test
    @DisplayName("статистика: без platform, appVersion, clientId, eventId, metric, value — ошибка по каждому")
    void statRequiredFields() {
        ClientStatBatchFO fo = new ClientStatBatchFO();
        fo.setItems(List.of(new ClientStatItemFO()));

        assertThat(validator.validate(fo)).extracting(v -> v.getPropertyPath().toString())
                .containsExactlyInAnyOrder("platform", "appVersion", "clientId",
                        "items[0].eventId", "items[0].metric", "items[0].value");
    }

    @Test
    @DisplayName("до входа: те же обязательные поля, clientId — тоже")
    void publicRequiresClientId() {
        PublicLogBatchFO fo = new PublicLogBatchFO();
        fo.setPlatform("android");
        fo.setAppVersion("5.12.0");
        fo.setItems(List.of(item("INFO", "x")));

        assertThat(validator.validate(fo)).extracting(v -> v.getPropertyPath().toString()).containsExactly("clientId");
    }

    private static Set<String> paths(ClientLogBatchFO fo) {
        Set<ConstraintViolation<ClientLogBatchFO>> violations = validator.validate(fo);
        return violations.stream().map(v -> v.getPropertyPath().toString())
                .map(path -> path.replaceAll("<map value>|<map key>", "").replaceAll("\\[[^\\]]*]\\.$", ""))
                .collect(Collectors.toSet());
    }

    private static ClientLogBatchFO batch(ClientLogItemFO... items) {
        ClientLogBatchFO fo = new ClientLogBatchFO();
        fo.setPlatform("android");
        fo.setAppVersion("5.12.0");
        fo.setClientId("install-42");
        fo.setItems(List.of(items));
        return fo;
    }

    private static ClientLogItemFO item(String level, String message) {
        ClientLogItemFO item = new ClientLogItemFO();
        item.setEventId(UUID.randomUUID().toString());
        item.setLevel(level);
        item.setMessage(message);
        return item;
    }
}
