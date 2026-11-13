package kz.janapost.dwhservice.util;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Маскирует персональные данные и секреты в логах мобильного приложения до публикации в Kafka:
 * токены ({@code Bearer ...}, JWT, {@code X-TokenInfo}), номера карт (с проверкой Луна),
 * телефоны KZ / UZ / KG / TJ.
 * <p>
 * Маскирование эвристическое: 11–13-значный номер, похожий на телефон или карту (например, epoch ms,
 * прошедший проверку Луна), тоже будет замаскирован. Для логов это допустимо: лучше потерять число, чем телефон.
 */
@Component
public class PiiMasker {
    static final String TOKEN_MASK = "***TOKEN***";
    static final String CARD_MASK = "***CARD***";
    static final String PHONE_MASK = "***PHONE***";

    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]+");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9\\-._~+/]+=*");
    private static final Pattern TOKEN_INFO = Pattern.compile("(?i)(x-tokeninfo[\"']?\\s*[:=]\\s*)(\"[^\"]*\"|\\{[^}]*}|\\S+)");
    /** 13–19 цифр, допускаются пробелы и дефисы между ними. */
    private static final Pattern CARD_CANDIDATE = Pattern.compile("(?<![\\d-])\\d(?:[ -]?\\d){12,18}(?![\\d-])");
    /** KZ: +7 / 7 / 8 и 10 цифр. */
    private static final Pattern PHONE_KZ = Pattern.compile("(?<![\\d+])(?:\\+7|7|8)(?:[\\s\\-()]*\\d){10}(?!\\d)");
    /** KG +996, UZ +998, TJ +992 и 9 цифр. */
    private static final Pattern PHONE_CIS = Pattern.compile("(?<![\\d+])\\+?99[268](?:[\\s\\-()]*\\d){9}(?!\\d)");

    private static final int CARD_MIN_DIGITS = 13;

    public String mask(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String masked = JWT.matcher(value).replaceAll(TOKEN_MASK);
        masked = BEARER.matcher(masked).replaceAll("Bearer " + TOKEN_MASK);
        masked = TOKEN_INFO.matcher(masked).replaceAll("$1" + Matcher.quoteReplacement(TOKEN_MASK));
        masked = maskCards(masked);
        masked = PHONE_CIS.matcher(masked).replaceAll(PHONE_MASK);
        masked = PHONE_KZ.matcher(masked).replaceAll(PHONE_MASK);
        return masked;
    }

    /** Маскирует значения; ключи не трогает — это имена полей приложения. */
    public Map<String, String> maskValues(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return values;
        }
        Map<String, String> masked = new LinkedHashMap<>();
        values.forEach((key, value) -> masked.put(key, mask(value)));
        return masked;
    }

    private String maskCards(String value) {
        Matcher matcher = CARD_CANDIDATE.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String digits = matcher.group().replaceAll("[ -]", "");
            String replacement = digits.length() >= CARD_MIN_DIGITS && luhnValid(digits) ? CARD_MASK : matcher.group();
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static boolean luhnValid(String digits) {
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubleIt) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }
}
