package kz.janapost.dwhservice.model.appsflyer;

import java.util.regex.Pattern;

/**
 * Формат AppsFlyer SDK ID: {@code <13-значный timestamp>-<случайные цифры>}, напр. {@code 1712345678901-1234567}.
 * То же правило, что в client-service: «мусорный» ID не попадает в identity.
 */
public final class AppsflyerIdFormat {
    public static final String REGEX = "^\\d{13}-\\d{1,20}$";
    private static final Pattern PATTERN = Pattern.compile(REGEX);

    private AppsflyerIdFormat() {
    }

    public static boolean isValid(String appsflyerId) {
        return appsflyerId != null && PATTERN.matcher(appsflyerId).matches();
    }
}
