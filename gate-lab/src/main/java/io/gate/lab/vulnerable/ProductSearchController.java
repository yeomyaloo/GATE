package io.gate.lab.vulnerable;

import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 상품 이름 검색 (대소문자 무시, 부분 일치). 숨김 상품은 보이지 않는다. */
@RestController
@Profile("vulnerable")
public class ProductSearchController {

    private final JdbcTemplate jdbc;

    public ProductSearchController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/products/search")
    public List<Map<String, Object>> search(@RequestParam(defaultValue = "") String q) {
        String sql = "SELECT id, name, price FROM products WHERE hidden = FALSE AND name ILIKE '%" + q + "%' "
                + "ORDER BY id";
        return jdbc.queryForList(sql);
    }
}
