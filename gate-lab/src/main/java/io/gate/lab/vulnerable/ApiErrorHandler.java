package io.gate.lab.vulnerable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** API 오류 응답. 디버깅이 쉽도록 예외 정보를 함께 돌려준다. */
@RestControllerAdvice
@Profile("vulnerable")
public class ApiErrorHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(body(e, e.getStatusCode().value()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> any(Exception e) {
        int status = e.getClass().getSimpleName().startsWith("HttpMessageNotReadable")
                || e.getClass().getSimpleName().startsWith("MethodArgumentTypeMismatch")
                ? HttpStatus.BAD_REQUEST.value() : HttpStatus.INTERNAL_SERVER_ERROR.value();
        return ResponseEntity.status(status).body(body(e, status));
    }

    private static Map<String, Object> body(Exception e, int status) {
        StringWriter trace = new StringWriter();
        e.printStackTrace(new PrintWriter(trace));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", Instant.now().toString());
        m.put("status", status);
        m.put("error", e.getClass().getName());
        m.put("message", e.getMessage());
        if (e.getCause() != null) {
            m.put("cause", e.getCause().toString());
        }
        m.put("trace", trace.toString());
        return m;
    }
}
