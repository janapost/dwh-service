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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Обязательны только level / message (логи) и metric / value (статистика); остальное проверяется, только если пришло. */
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
    @DisplayName("минимальная пачка: только level и message — валидна")
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
        ClientLogBatchFO fo = new ClientLogBatchFO();
        fo.setItems(List.of());

        assertThat(paths(fo)).containsExactly("items");
    }

    @Test
    @DisplayName("eventId, если пришёл, — только UUID")
    void eventIdFormat() {
        ClientLogItemFO item = item("INFO", "x");
        item.setEventId("not-a-uuid");

        assertThat(paths(batch(item))).containsExactly("items[0].eventId");
    }

    @Test
    @DisplayName("platform, если пришла, — только ios / android")
    void platformFormat() {
        ClientLogBatchFO fo = batch(item("INFO", "x"));
        fo.setPlatform("windows");

        assertThat(paths(fo)).containsExactly("platform");
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
    @DisplayName("статистика: без metric или value — ошибка")
    void statRequiredFields() {
        ClientStatBatchFO fo = new ClientStatBatchFO();
        fo.setItems(List.of(new ClientStatItemFO()));

        assertThat(validator.validate(fo)).extracting(v -> v.getPropertyPath().toString())
                .containsExactlyInAnyOrder("items[0].metric", "items[0].value");
    }

    @Test
    @DisplayName("до входа: deviceId необязателен, но не длиннее 64")
    void publicDeviceId() {
        PublicLogBatchFO fo = new PublicLogBatchFO();
        fo.setItems(List.of(item("INFO", "x")));
        assertThat(validator.validate(fo)).isEmpty();

        fo.setDeviceId("d".repeat(65));
        assertThat(validator.validate(fo)).extracting(v -> v.getPropertyPath().toString()).containsExactly("deviceId");
    }

    private static Set<String> paths(ClientLogBatchFO fo) {
        Set<ConstraintViolation<ClientLogBatchFO>> violations = validator.validate(fo);
        return violations.stream().map(v -> v.getPropertyPath().toString())
                .map(path -> path.replaceAll("<map value>|<map key>", "").replaceAll("\\[[^\\]]*]\\.$", ""))
                .collect(java.util.stream.Collectors.toSet());
    }

    private static ClientLogBatchFO batch(ClientLogItemFO... items) {
        ClientLogBatchFO fo = new ClientLogBatchFO();
        fo.setItems(List.of(items));
        return fo;
    }

    private static ClientLogItemFO item(String level, String message) {
        ClientLogItemFO item = new ClientLogItemFO();
        item.setLevel(level);
        item.setMessage(message);
        return item;
    }
}
