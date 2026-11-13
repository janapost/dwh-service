package kz.janapost.dwhservice.model.fo.client.v1;

/** Сведения о приложении, общие для пачек логов и статистики. Все поля необязательные. */
public interface MobileClientInfo {
    String PLATFORM_REGEX = "(?i)ios|android";
    String UUID_REGEX = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    String getPlatform();

    String getAppVersion();

    String getDeviceModel();

    String getOsVersion();
}
