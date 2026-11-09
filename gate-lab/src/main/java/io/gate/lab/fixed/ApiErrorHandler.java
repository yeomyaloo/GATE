package io.gate.lab.fixed;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/**
 * API 오류 응답 (RFC 9457 ProblemDetail).
 * 클라이언트에는 일반 메시지와 추적 ID만 준다. 예외 클래스·SQL·스택 트레이스는 서버 로그에만 남기고,
 * 추적 ID로 로그와 응답을 이어 본다.
 */
@RestControllerAdvice
@Profile("fixed")
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail status(ResponseStatusException e) {
        return problem(e.getStatusCode(), e, false);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail badRequest(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, e, false);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail any(Exception e) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, e, true);
    }

    private static ProblemDetail problem(HttpStatusCode status, Exception e, boolean unexpected) {
        String traceId = UUID.randomUUID().toString();
        if (unexpected) {
            log.error("request failed [traceId={}]", traceId, e);
        } else {
            log.info("request rejected [traceId={}]: {}", traceId, e.toString());
        }
        HttpStatus known = HttpStatus.resolve(status.value());
        ProblemDetail p = ProblemDetail.forStatus(status);
        p.setTitle(known != null ? known.getReasonPhrase() : "Error");
        p.setDetail(unexpected ? "An unexpected error occurred." : "The request could not be processed.");
        p.setProperty("traceId", traceId);
        return p;
    }
}
