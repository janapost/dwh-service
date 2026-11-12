package kz.janapost.dwhservice.exception;

public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException() {
        super("Unauthorized!");
    }
}
