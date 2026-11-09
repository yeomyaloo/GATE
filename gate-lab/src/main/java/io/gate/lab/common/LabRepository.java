package io.gate.lab.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** 사용자·주문 저장소. 모든 쿼리는 바인딩 변수를 쓴다. */
@Repository
public class LabRepository {

    public record User(long id, String username, String displayName, String email, String passwordHash,
            String role) {
    }

    public record Order(long id, long userId, String owner, long productId, String productName, int quantity,
            String note, String shippingAddress, Instant createdAt) {
    }

    private static final String ORDER_SELECT = "SELECT o.id, o.user_id, u.username AS owner, o.product_id, "
            + "p.name AS product_name, o.quantity, o.note, o.shipping_address, o.created_at "
            + "FROM orders o JOIN users u ON u.id = o.user_id JOIN products p ON p.id = o.product_id ";

    private final JdbcTemplate jdbc;

    public LabRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<User> findUser(String username) {
        return jdbc.query("SELECT * FROM users WHERE username = ?", LabRepository::user, username).stream()
                .findFirst();
    }

    public Optional<Order> findOrder(long id) {
        return jdbc.query(ORDER_SELECT + "WHERE o.id = ?", LabRepository::order, id).stream().findFirst();
    }

    public List<Order> ordersOf(long userId) {
        return jdbc.query(ORDER_SELECT + "WHERE o.user_id = ? ORDER BY o.id", LabRepository::order, userId);
    }

    public boolean productExists(long productId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE id = ? AND hidden = FALSE",
                Integer.class, productId);
        return n != null && n > 0;
    }

    public long createOrder(long userId, long productId, int quantity, String note, String address) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("INSERT INTO orders (user_id, product_id, quantity, note, shipping_address) "
                    + "VALUES (?, ?, ?, ?, ?)", new String[] {"id"});
            ps.setLong(1, userId);
            ps.setLong(2, productId);
            ps.setInt(3, quantity);
            ps.setString(4, note);
            ps.setString(5, address);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public void updateOrder(long id, int quantity, String note, String address) {
        jdbc.update("UPDATE orders SET quantity = ?, note = ?, shipping_address = ? WHERE id = ?", quantity, note,
                address, id);
    }

    public void deleteOrder(long id) {
        jdbc.update("DELETE FROM orders WHERE id = ?", id);
    }

    private static User user(ResultSet rs, int i) throws SQLException {
        return new User(rs.getLong("id"), rs.getString("username"), rs.getString("display_name"),
                rs.getString("email"), rs.getString("password_hash"), rs.getString("role"));
    }

    private static Order order(ResultSet rs, int i) throws SQLException {
        return new Order(rs.getLong("id"), rs.getLong("user_id"), rs.getString("owner"), rs.getLong("product_id"),
                rs.getString("product_name"), rs.getInt("quantity"), rs.getString("note"),
                rs.getString("shipping_address"), rs.getTimestamp("created_at").toInstant());
    }

    /** 주문 응답 모양 (두 버전 공통). */
    public static java.util.Map<String, Object> view(Order o) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("id", o.id());
        m.put("owner", o.owner());
        m.put("productId", o.productId());
        m.put("productName", o.productName());
        m.put("quantity", o.quantity());
        m.put("note", o.note());
        m.put("shippingAddress", o.shippingAddress());
        m.put("createdAt", o.createdAt().toString());
        return m;
    }
}
