package kz.janapost.dwhservice.model.fo.client.v1;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/** Одна запись лога. Обязательны только {@code level} и {@code message}. */
@Data
public class ClientLogItemFO {
    public static final String LEVEL_REGEX = "(?i)INFO|WARN|ERROR";

    /** UUID записи. Приложение создаёт его один раз и повторяет при повторной отправке; нет — создаст dwh. */
    @Pattern(regexp = MobileClientInfo.UUID_REGEX, message = "must be UUID")
    private String eventId;

    /** Время события, epoch ms; нет — время сервера. */
    @Positive
    private Long timestamp;

    @NotBlank
    @Pattern(regexp = LEVEL_REGEX, message = "must be INFO, WARN or ERROR")
    private String level;

    @NotBlank
    @Size(max = 4000)
    private String message;

    @Size(max = 16000)
    private String stackTrace;

    @Size(max = 128)
    private String traceId;

    @Size(max = 30)
    private Map<@NotBlank @Size(max = 64) String, @Size(max = 512) String> attributes;
}
