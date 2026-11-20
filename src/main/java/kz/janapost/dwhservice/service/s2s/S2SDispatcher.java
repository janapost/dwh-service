package kz.janapost.dwhservice.service.s2s;

import kz.janapost.dwhservice.model.s2s.S2SEvent;
import kz.janapost.dwhservice.model.s2s.S2SIdentity;

import java.util.List;

/**
 * Раздаёт события S2S-провайдерам: основному ({@code default-bean}) и параллельным ({@code parallel-beans}).
 * Ошибка одного провайдера не мешает другим; если кто-то упал, после всех вызовов бросается
 * {@link kz.janapost.dwhservice.exception.S2SDeliveryException} — консьюмер повторит событие (повтор безопасен).
 */
public interface S2SDispatcher {

    void send(S2SEvent event);

    /** ID устройства — всем зарегистрированным провайдерам, даже не активным: ID копятся до начала отправки. */
    void saveIdentity(S2SIdentity identity);

    /** Активные провайдеры: основной, затем параллельные. */
    List<S2SService> activeServices();
}
