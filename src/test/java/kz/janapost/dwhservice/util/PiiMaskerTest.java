package kz.janapost.dwhservice.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PiiMaskerTest {

    private final PiiMasker masker = new PiiMasker();

    @ParameterizedTest
    @ValueSource(strings = {
            "+77011234567", "87011234567", "+7 (701) 123-45-67", "8 701 123 45 67", // KZ
            "+996555123456", "+996 555 123 456",                                    // KG
            "+998901234567", "+998 90 123 45 67",                                   // UZ
            "+992931234567"                                                         // TJ
    })
    @DisplayName("телефоны KZ / KG / UZ / TJ маскируются")
    void phones(String phone) {
        assertThat(masker.mask("call " + phone + " now")).isEqualTo("call " + PiiMasker.PHONE_MASK + " now");
    }

    @ParameterizedTest
    @ValueSource(strings = {"4111111111111111", "4111 1111 1111 1111", "5500-0000-0000-0004"})
    @DisplayName("номера карт, прошедшие проверку Луна, маскируются")
    void cards(String card) {
        assertThat(masker.mask("card " + card)).isEqualTo("card " + PiiMasker.CARD_MASK);
    }

    @Test
    @DisplayName("16 цифр без проверки Луна не считаются картой")
    void nonLuhnNumberIsKept() {
        assertThat(masker.mask("order 4111111111111112")).isEqualTo("order 4111111111111112");
    }

    @Test
    @DisplayName("Bearer, JWT и X-TokenInfo маскируются")
    void tokens() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJKUDEifQ.c2lnbmF0dXJl";

        assertThat(masker.mask("Authorization: Bearer abc.def-123")).isEqualTo("Authorization: Bearer " + PiiMasker.TOKEN_MASK);
        assertThat(masker.mask("token=" + jwt)).isEqualTo("token=" + PiiMasker.TOKEN_MASK);
        assertThat(masker.mask("X-TokenInfo: {\"jpid\":\"JP1\",\"phone\":\"x\"}"))
                .isEqualTo("X-TokenInfo: " + PiiMasker.TOKEN_MASK);
    }

    @Test
    @DisplayName("обычный текст, короткие числа и номера заказов не меняются")
    void plainTextIsKept() {
        String text = "Payment screen crashed: order JP4595925, attempt 3, code 500";

        assertThat(masker.mask(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("null и пустая строка возвращаются как есть")
    void nullAndEmpty() {
        assertThat(masker.mask(null)).isNull();
        assertThat(masker.mask("")).isEmpty();
    }

    @Test
    @DisplayName("в map маскируются значения, ключи не трогаются")
    void maskValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("phone", "+77011234567");
        values.put("screen", "payment");

        assertThat(masker.maskValues(values))
                .containsEntry("phone", PiiMasker.PHONE_MASK)
                .containsEntry("screen", "payment");
    }
}
