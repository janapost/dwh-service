package kz.janapost.dwhservice.model.s2s;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import kz.janapost.dwhservice.model.appsflyer.DeepLinkData;

/**
 * Что приложение прислало об устройстве ({@code event_s2s}, {@code eventName = s2s_identity}): вход, регистрация,
 * {@code /client/v1/appsflyer/identity/sync}. Каждый провайдер берёт свои поля, остальное игнорирует.
 * Формат уже проверен в client-service; здесь битые значения пропускаются с логом.
 *
 * @param jpid           клиент
 * @param appsflyerId    AppsFlyer SDK ID
 * @param deviceOs       {@code ios} / {@code android}
 * @param deepLinkData   атрибуция установки (пишется, только если ещё не сохранена)
 * @param userCreateTime время создания аккаунта, epoch ms; {@code null} — неизвестно
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record S2SIdentity(
        String jpid,
        String appsflyerId,
        String deviceOs,
        DeepLinkData deepLinkData,
        Long userCreateTime
) {
}
