package kz.janapost.dwhservice.service.s2s;

import kz.janapost.dwhservice.model.s2s.S2SEvent;
import kz.janapost.dwhservice.model.s2s.S2SIdentity;

/**
 * Контракт одного S2S-провайдера (AppsFlyer, позже Adjust). Реализация — Spring-бин с именем провайдера
 * ({@code appsflyer_s2s_sender}); какие провайдеры работают, выбирает {@link S2SDispatcher} по свойствам
 * {@code kz.janapost.dwh-service.s2s.default-bean} / {@code parallel-beans}.
 * <p>
 * Повторный вызов с тем же событием безопасен: каждый провайдер ведёт свой журнал с ключом дедупликации.
 */
public interface S2SService {

    /** Имя Spring-бина провайдера. */
    String beanName();

    /**
     * Сохраняет ID устройства: провайдер берёт свои поля, остальное игнорирует; битые значения пропускаются с логом.
     * Ошибка хранилища пробрасывается — событие Kafka будет повторено.
     */
    void saveIdentity(S2SIdentity identity);

    /**
     * Резервирует событие в журнале провайдера и отправляет его. Ошибка отправки сохраняется в журнале для ретрая;
     * ошибка до резерва (хранилище недоступно) пробрасывается — событие Kafka будет повторено.
     */
    void send(S2SEvent event);

    /** Повторная отправка неудачных событий. Вызывается планировщиком под Redis-блокировкой провайдера. */
    void retryPending();

    /** Очистка старых завершённых строк журнала. Вызывается планировщиком под Redis-блокировкой провайдера. */
    void cleanup();
}
