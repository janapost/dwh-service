package kz.janapost.dwhservice.model.fo.client.v1;

import jakarta.validation.Valid;
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
    @Pattern(regexp = PLATFORM_REGEX, message = "must be ios or android")
    private String platform;

    @Size(max = 32)
    private String appVersion;

    @Size(max = 64)
    private String deviceModel;

    @Size(max = 32)
    private String osVersion;

    @NotEmpty
    private List<@NotNull @Valid ClientLogItemFO> items;
}
