package kz.janapost.dwhservice.exception;

/**
 * Запрос не прошёл проверку, которую нельзя выразить аннотациями (например, лимит из свойств).
 * Отдаётся как {@code -1} с текстом ошибки.
 */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
