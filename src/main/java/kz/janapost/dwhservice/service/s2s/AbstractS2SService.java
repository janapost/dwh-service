package kz.janapost.dwhservice.service.s2s;

import kz.janapost.dwhservice.model.s2s.S2SEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Общий конвейер S2S-провайдера (template method). Наследник отвечает за четыре вещи: ID устройства, строку журнала,
 * HTTP-вызов и работу с журналом. Порядок шагов одинаковый для всех провайдеров:
 * <ol>
 *     <li>{@link #prepare} — провайдер может отбросить или сузить событие (например, старые строки af_purchase);</li>
 *     <li>нет ID устройства — строка без ID ({@code NO_IDENTITY}), уйдёт ретраю, когда ID придёт;</li>
 *     <li>есть ID — строка резервируется по ключу дедупликации <b>до</b> HTTP-вызова: повтор события не уходит дважды;</li>
 *     <li>отправка выключена ({@link #sendEnabled}) — строка ждёт включения (теневой режим);</li>
 *     <li>HTTP-вызов, результат — в журнал.</li>
 * </ol>
 * Ошибки до резерва (хранилище недоступно) пробрасываются: консьюмер повторит событие, повтор безопасен из-за резерва.
 * Ошибки HTTP-вызова сохраняются в строке журнала, их повторяет {@link #retryPending()}.
 *
 * @param <I> ID устройства в терминах провайдера
 * @param <R> строка журнала провайдера
 */
@Slf4j
public abstract class AbstractS2SService<I, R> implements S2SService {

    @Override
    public final void send(S2SEvent event) {
        if (event == null || event.type() == null || isBlank(event.jpid()) || !supports(event)) {
            return;
        }
        S2SEvent prepared = prepare(event);
        if (prepared == null) {
            return;
        }
        I identity = resolveIdentity(prepared.jpid());
        if (identity == null) {
            // «вставка → повторное чтение» здесь и «сохранение ID → перевод» в saveIdentity: хотя бы одна сторона
            // увидит другую, если ID пришёл между нашим чтением и вставкой
            if (reserve(newRow(prepared, null))) {
                promoteIfIdentityAppeared(prepared.jpid());
            }
            return;
        }
        R row = newRow(prepared, identity);
        if (!reserve(row)) {
            return;
        }
        if (!sendEnabled()) {
            holdUnsent(row);
            return;
        }
        sendRow(row);
    }

    @Override
    public final void retryPending() {
        if (!sendEnabled()) {
            return;
        }
        List<R> rows = findForRetry();
        if (!rows.isEmpty()) {
            log.info("[S2S:{}] retry: {} rows", beanName(), rows.size());
        }
        for (R row : rows) {
            String skipReason = skipReason(row);
            if (skipReason != null) {
                markSkipped(row, skipReason);
                continue;
            }
            // ретрай на двух подах (запуск дольше блокировки) не отправит одну строку дважды
            if (!claim(row)) {
                continue;
            }
            sendRow(row);
        }
    }

    /** HTTP-вызов и его результат в журнал. Строка уже зарезервирована, поэтому ошибка не пробрасывается. */
    private void sendRow(R row) {
        S2SSendResult result;
        try {
            result = doSend(row);
        } catch (Exception e) {
            result = S2SSendResult.failed(e);
        }
        if (!result.sent()) {
            log.warn("[S2S:{}] send failed: {}", beanName(), result.error());
        }
        afterSend(row, result);
    }

    protected static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // --- событие и ID устройства ---

    /** Провайдер отправляет такие события. */
    protected abstract boolean supports(S2SEvent event);

    /** Событие для отправки или {@code null}, если отправлять нечего. По умолчанию — как есть. */
    protected S2SEvent prepare(S2SEvent event) {
        return event;
    }

    /** ID устройства клиента или {@code null}, если его ещё нет. */
    protected abstract I resolveIdentity(String jpid);

    /** Строка журнала; {@code identity == null} — строка без ID устройства. */
    protected abstract R newRow(S2SEvent event, I identity);

    /** HTTP-вызов провайдера по строке журнала. */
    protected abstract S2SSendResult doSend(R row);

    // --- журнал ---

    /**
     * Резервирует строку журнала по ключу дедупликации.
     *
     * @return {@code false}, если такое бизнес-событие уже есть в журнале
     */
    protected abstract boolean reserve(R row);

    protected abstract void afterSend(R row, S2SSendResult result);

    /** Отправка выключена: строка ждёт включения, попытка не тратится. */
    protected abstract void holdUnsent(R row);

    /** Перевод строк без ID в ретрай, если ID уже сохранён. Повторный вызов безопасен. */
    protected abstract void promoteIfIdentityAppeared(String jpid);

    protected abstract List<R> findForRetry();

    /**
     * Атомарно забирает строку ретрая под отправку.
     *
     * @return {@code false}, если её уже забрал другой под — строку пропустить
     */
    protected abstract boolean claim(R row);

    /** Причина не отправлять строку при ретрае или {@code null}. */
    protected String skipReason(R row) {
        return null;
    }

    protected void markSkipped(R row, String reason) {
        throw new UnsupportedOperationException("skip is not supported by " + beanName());
    }

    protected abstract boolean sendEnabled();
}
