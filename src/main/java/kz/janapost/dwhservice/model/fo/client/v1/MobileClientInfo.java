package kz.janapost.dwhservice.model.fo.client.v1;

/**
 * Сведения о приложении, общие для пачек логов и статистики.
 * Обязательны {@code platform}, {@code appVersion}, {@code clientId}; {@code deviceModel} и {@code osVersion} — нет.
 */
public interface MobileClientInfo {
    String PLATFORM_REGEX = "(?i)ios|android";
    String UUID_REGEX = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
    /** ID установки приложения: идёт в ключ Redis лимита частоты, поэтому только безопасные символы. */
    String CLIENT_ID_REGEX = "[A-Za-z0-9._:-]+";

    String getPlatform();

    String getAppVersion();

    String getClientId();

    String getDeviceModel();

    String getOsVersion();
}
