package com.github.guoyyit.orderlifecycleomsjava.api;

import com.github.guoyyit.orderlifecycleomsjava.domain.RejectReason;
import com.github.guoyyit.orderlifecycleomsjava.exception.OrderStateException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 将领域拒绝映射为可区分的 HTTP 响应：响应体携带 {@link RejectReason} 原因码。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(OrderStateException.class)
    public ResponseEntity<Map<String, Object>> handle(OrderStateException ex) {
        HttpStatus status = switch (ex.reason()) {
            case UNKNOWN_ORDER -> HttpStatus.NOT_FOUND;
            case ILLEGAL_TRANSITION, ORDER_TERMINAL, DUPLICATE_REQUEST -> HttpStatus.CONFLICT;
            case INVALID_PARAMETER -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(Map.of("reason", ex.reason().name(), "message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArg(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("reason", "INVALID_PARAMETER", "message", ex.getMessage()));
    }
}
