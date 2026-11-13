package kz.janapost.dwhservice.model.fo.client.v1;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/** Одно значение метрики. Обязательны только {@code metric} и {@code value}. */
@Data
public class ClientStatItemFO {
    @Pattern(regexp = MobileClientInfo.UUID_REGEX, message = "must be UUID")
    private String eventId;

    /** Время замера, epoch ms; нет — время сервера. */
    @Positive
    private Long timestamp;

    @NotBlank
    @Size(max = 128)
    private String metric;

    @NotNull
    private Double value;

    @Size(max = 30)
    private Map<@NotBlank @Size(max = 64) String, @Size(max = 512) String> tags;
}
