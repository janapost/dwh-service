package kz.janapost.dwhservice.model.appsflyer;

/** Платформа устройства: по ней выбирается app id AppsFlyer. Хранится в журнале по имени. */
public enum AppsflyerPlatform {
    ANDROID, IOS;

    /** {@code ios} / {@code android} в любом регистре; иначе {@code null}. */
    public static AppsflyerPlatform of(String deviceOs) {
        if ("ios".equalsIgnoreCase(deviceOs)) {
            return IOS;
        }
        if ("android".equalsIgnoreCase(deviceOs)) {
            return ANDROID;
        }
        return null;
    }
}
