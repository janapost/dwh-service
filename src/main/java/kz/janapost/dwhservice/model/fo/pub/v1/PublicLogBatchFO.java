package kz.janapost.dwhservice.model.fo.pub.v1;

import jakarta.validation.constraints.Size;
import kz.janapost.dwhservice.model.fo.client.v1.ClientLogBatchFO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Пачка логов приложения до входа. {@code deviceId} — ID установки, нужен для лимита частоты. */
@Data
@EqualsAndHashCode(callSuper = true)
public class PublicLogBatchFO extends ClientLogBatchFO {
    @Size(max = 64)
    private String deviceId;
}
