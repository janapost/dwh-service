package kz.janapost.dwhservice.service.s2s;

/**
 * Итог одного HTTP-вызова провайдера.
 *
 * @param sent     {@code true} — провайдер принял событие
 * @param response тело ответа (как есть)
 * @param error    причина неудачи
 */
public record S2SSendResult(boolean sent, String response, String error) {

    public static S2SSendResult sent(String response) {
        return new S2SSendResult(true, response, null);
    }

    public static S2SSendResult failed(String error) {
        return new S2SSendResult(false, null, error);
    }

    public static S2SSendResult failed(Exception e) {
        return failed(e.getClass().getSimpleName() + ": " + e.getMessage());
    }
}
