package kz.janapost.dwhservice.exception;

public class ForbiddenException extends RuntimeException {
    public ForbiddenException() {
        super("Forbidden!");
    }
}
