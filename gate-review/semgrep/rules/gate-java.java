// Semgrep 규칙 테스트 (semgrep --test). "ruleid:"는 걸려야 하는 줄, "ok:"는 걸리면 안 되는 줄이다.
// 실습장 코드와 다른 이름·모양으로 쓴 일반 예제다.
package example;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
class CustomerController {

    private final JdbcTemplate jdbc;

    CustomerController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/customers")
    List<Map<String, Object>> byCity(@RequestParam String city) {
        String q = "SELECT * FROM customers WHERE city = '" + city + "'";
        // ruleid: gate-java-sql-string-concat
        return jdbc.queryForList(q);
    }

    @GetMapping("/customers/{id}")
    Map<String, Object> byId(@PathVariable("id") String id) {
        // ruleid: gate-java-sql-string-concat
        return jdbc.queryForMap("SELECT * FROM customers WHERE id = " + id);
    }

    @GetMapping("/customers/search")
    List<Map<String, Object>> search(@RequestParam(defaultValue = "") String name) {
        // ok: gate-java-sql-string-concat
        return jdbc.queryForList("SELECT * FROM customers WHERE name LIKE ?", "%" + name + "%");
    }

    @DeleteMapping("/customers/{id}")
    void delete(@PathVariable long id) {
        // ok: gate-java-sql-string-concat
        jdbc.update("DELETE FROM customers WHERE id = ?", id);
    }

    List<Map<String, Object>> internal(String table) {
        // ok: gate-java-sql-string-concat
        return jdbc.queryForList("SELECT * FROM " + table);
    }
}

class SessionReader {

    String subjectOf(String authorization) {
        String[] segments = authorization.substring(7).split("\\.");
        // ruleid: gate-java-jwt-payload-trusted-without-verification
        String json = new String(Base64.getUrlDecoder().decode(segments[1]), StandardCharsets.UTF_8);
        return json;
    }

    String verifiedSubjectOf(String authorization, Verifier verifier) {
        String[] segments = authorization.substring(7).split("\\.");
        verifier.verify(authorization);
        // ok: gate-java-jwt-payload-trusted-without-verification
        return new String(Base64.getUrlDecoder().decode(segments[1]), StandardCharsets.UTF_8);
    }

    String firstSegment(String csv) {
        String[] parts = csv.split(",");
        // ok: gate-java-jwt-payload-trusted-without-verification
        return parts[0];
    }

    interface Verifier {
        void verify(String token);
    }
}

@RestControllerAdvice
class LeakyErrors {

    @ExceptionHandler(Exception.class)
    Map<String, Object> handle(Exception ex) {
        Map<String, Object> body = new HashMap<>();
        // ruleid: gate-java-exception-details-in-response
        body.put("detail", ex.getMessage());
        StringWriter sw = new StringWriter();
        // ruleid: gate-java-exception-details-in-response
        ex.printStackTrace(new PrintWriter(sw));
        body.put("stack", sw.toString());
        return body;
    }
}

@RestControllerAdvice
class SafeErrors {

    @ExceptionHandler(Exception.class)
    Map<String, Object> handle(Exception ex) {
        Map<String, Object> body = new HashMap<>();
        // ok: gate-java-exception-details-in-response
        body.put("detail", "An unexpected error occurred.");
        return body;
    }
}

class NotAnAdvice {

    Map<String, Object> describe(Exception ex) {
        Map<String, Object> m = new HashMap<>();
        // ok: gate-java-exception-details-in-response
        m.put("message", ex.getMessage());
        return m;
    }
}
