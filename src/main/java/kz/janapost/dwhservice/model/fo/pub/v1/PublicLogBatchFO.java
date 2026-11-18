package kz.janapost.dwhservice.model.fo.pub.v1;

import kz.janapost.dwhservice.model.fo.client.v1.ClientLogBatchFO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Пачка логов приложения до входа. Поля — как у {@link ClientLogBatchFO}; {@code clientId} здесь ещё и ключ
 * лимита частоты по устройству. Отдельный класс — под свои лимиты пачки и тела.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PublicLogBatchFO extends ClientLogBatchFO {
}
