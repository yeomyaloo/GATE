package io.gate.lab.fixed;

import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 상품 이름 검색 (대소문자 무시, 부분 일치). 숨김 상품은 보이지 않는다.
 * 검색어는 바인딩 변수로만 전달하고, LIKE 특수 문자(% _ \)는 이스케이프해 글자 그대로 찾는다.
 */
@RestController
@Profile("fixed")
public class ProductSearchController {

    static final int MAX_QUERY = 100;

    private final JdbcTemplate jdbc;

    public ProductSearchController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/products/search")
    public List<Map<String, Object>> search(@RequestParam(defaultValue = "") String q) {
        if (q.length() > MAX_QUERY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query too long");
        }
        String pattern = "%" + escapeLike(q) + "%";
        return jdbc.queryForList("SELECT id, name, price FROM products WHERE hidden = FALSE "
                + "AND name ILIKE ? ESCAPE '\\' ORDER BY id", pattern);
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
