package kz.janapost.dwhservice.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kz.janapost.commonsdk.bean.GenericResult;
import kz.janapost.commonsdk.exception.UnimportantException;
import kz.janapost.simplelogger.SimpleLogger;
import kz.janapost.util.ExceptionUtil;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    @ResponseBody
    public GenericResult<?> handleException(HttpServletRequest request, Exception e, HttpServletResponse response) {
        if (e instanceof UnauthorizedException) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return new GenericResult<>(-401, "Unauthorized!", null);
        }
        if (e instanceof ForbiddenException) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return new GenericResult<>(-403, "Forbidden!", null);
        }
        if (e instanceof HttpRequestMethodNotSupportedException) {
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return new GenericResult<>(-405, "Method is not supported!", null);
        }
        if (e instanceof UnimportantException) {
            return new GenericResult<>(-2, "Unimportant error: " + e.getMessage(), null);
        }
        if (e instanceof MethodArgumentNotValidException methodArgumentNotValidException) {
            return new GenericResult<>(-1, "Validation error!", validationErrorData(methodArgumentNotValidException));
        }
        if (e instanceof BadRequestException) {
            return new GenericResult<>(-1, e.getMessage(), null);
        }
        if (e instanceof TooManyRequestsException tooManyRequestsException) {
            return new GenericResult<>(-429, "Too many requests, retry later",
                    Map.of("retryAfterSec", tooManyRequestsException.getRetryAfterSec()));
        }
        if (hasCause(e, PayloadTooLargeException.class)) {
            return new GenericResult<>(-413, "Request body is too large, split the batch", null);
        }
        if (e instanceof HttpMessageNotReadableException || e instanceof ClientAbortException) {
            return new GenericResult<>(-101, e.getMessage(), null);
        }
        SimpleLogger.getInstanceDayly("exception-all-exception[delete10]", getClass())
                .append(request.getRequestURI(), ExceptionUtil.getStackTrace(e));
        return new GenericResult<>(-90, e.getMessage(), null);
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable current = e; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
            if (current.getCause() == current) {
                return false;
            }
        }
        return false;
    }

    private Map<String, Object> validationErrorData(MethodArgumentNotValidException exception) {
        List<Map<String, String>> details = exception.getBindingResult()
                .getAllErrors()
                .stream()
                .map(error -> {
                    Map<String, String> map = new HashMap<>();
                    String field = error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
                    map.put(field, error.getDefaultMessage());
                    return map;
                })
                .toList();
        Map<String, Object> data = new HashMap<>();
        data.put("errorCount", exception.getErrorCount());
        data.put("details", details);
        return data;
    }
}
