package demo.shop;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 주문 내역 내보내기: 내 주문 목록(상태로 거르기)과 주문 한 건 상세. */
@RestController
public class OrderExportController {

    private final JdbcTemplate jdbc;

    public OrderExportController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/exports/orders")
    public List<Map<String, Object>> myOrders(Principal user, @RequestParam(defaultValue = "PAID") String status) {
        String sql = "SELECT id, item, amount, status FROM orders WHERE owner = ? AND status = '" + status
                + "' ORDER BY id";
        return jdbc.queryForList(sql, user.getName());
    }

    @GetMapping("/api/exports/orders/{id}")
    public Map<String, Object> order(Principal user, @PathVariable long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, owner, item, amount, status, card_last4, address FROM orders WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return rows.get(0);
    }
}
