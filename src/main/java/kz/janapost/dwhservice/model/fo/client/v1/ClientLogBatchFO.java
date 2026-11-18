package kz.janapost.dwhservice.model.fo.client.v1;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Пачка логов приложения. Максимальное число записей задаётся свойствами
 * ({@code mobile.max-items} / {@code mobile.public-max-items}) и проверяется в сервисе.
 */
@Data
public class ClientLogBatchFO implements MobileClientInfo {
    @NotBlank
    @Pattern(regexp = PLATFORM_REGEX, message = "must be ios or android")
    private String platform;

    /** Без формата: у сборок бывают суффиксы ({@code 5.12.0+345}, {@code 5.12.0-beta}). */
    @NotBlank
    @Size(max = 32)
    private String appVersion;

    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = CLIENT_ID_REGEX, message = "must contain only letters, digits and . _ : -")
    private String clientId;

    @Size(max = 64)
    private String deviceModel;

    @Size(max = 32)
    private String osVersion;

    @NotEmpty
    private List<@NotNull @Valid ClientLogItemFO> items;
}
