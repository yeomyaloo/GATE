package io.gate.prompt.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gate.prompt.GuardResult;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class SqlGuardTest {

    static final SqlPolicy POLICY = new SqlPolicy("orders",
            List.of("id", "product_id", "quantity", "note", "created_at"), "user_id", 50);
    static final SqlGuard GUARD = new SqlGuard(POLICY);
    static final long ALICE = 1L;

    static SqlGuard.BoundQuery allowed(String sql) {
        GuardResult<SqlGuard.BoundQuery> r = GUARD.check(sql, ALICE);
        assertTrue(r.allowed(), () -> sql + " -> " + r.rejection().orElseThrow());
        return r.allowedValue().orElseThrow();
    }

    static String blockedRule(String sql) {
        GuardResult<SqlGuard.BoundQuery> r = GUARD.check(sql, ALICE);
        assertFalse(r.allowed(), () -> sql + " was allowed as " + r.allowedValue().orElseThrow());
        return r.rejection().orElseThrow().ruleId();
    }

    @Test
    void normalQueryIsRebuiltWithOwnerScopeAndBoundValues() {
        SqlGuard.BoundQuery q = allowed("SELECT id, quantity FROM orders WHERE quantity >= 2 AND note LIKE '%gift%' "
                + "ORDER BY created_at DESC LIMIT 10");
        assertEquals("SELECT id, quantity FROM orders WHERE user_id = ? AND (quantity >= ? AND note LIKE ?) "
                + "ORDER BY created_at DESC LIMIT 10", q.sql());
        assertEquals(List.of(ALICE, 2L, "%gift%"), q.params());
    }

    @Test
    void starBecomesTheAllowedColumnsAndLimitIsCapped() {
        SqlGuard.BoundQuery q = allowed("select * from ORDERS limit 100000");
        assertEquals("SELECT id, product_id, quantity, note, created_at FROM orders WHERE user_id = ? LIMIT 50",
                q.sql());
        assertEquals(List.of(ALICE), q.params());
    }

    @Test
    void anotherUsersRowsStayInvisibleWhateverTheConditionSays() {
        // AI가 "user_id = 2 OR 1=1"을 써도 관문이 붙인 user_id = 현재 사용자 AND (...) 때문에 남의 행은 안 보인다
        SqlGuard.BoundQuery q = allowed("SELECT id FROM orders WHERE user_id = 2 OR quantity > 0");
        assertEquals("SELECT id FROM orders WHERE user_id = ? AND (user_id = ? OR quantity > ?) LIMIT 50", q.sql());
        assertEquals(List.of(ALICE, 2L, 0L), q.params());
    }

    @Test
    void quotesInsideValuesStayData() {
        SqlGuard.BoundQuery q = allowed("SELECT id FROM orders WHERE note = 'it''s a gift'");
        assertEquals(List.of(ALICE, "it's a gift"), q.params());
        assertFalse(q.sql().contains("gift"), "values never appear in the SQL text");
    }

    @Test
    void inBetweenNotAndNullChecksAreAllowed() {
        SqlGuard.BoundQuery q = allowed("SELECT id FROM orders WHERE id IN (1, 2, 3) AND NOT (quantity BETWEEN 1 AND 3)"
                + " AND note IS NOT NULL AND quantity <> -1");
        assertEquals("SELECT id FROM orders WHERE user_id = ? AND (id IN (?, ?, ?) AND NOT (quantity BETWEEN ? AND ?)"
                + " AND note IS NOT NULL AND quantity <> ?) LIMIT 50", q.sql());
        assertEquals(List.of(ALICE, 1L, 2L, 3L, 1L, 3L, -1L), q.params());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            DROP TABLE orders                                                        | PROMPT-SQL-NOT-SELECT
            DELETE FROM orders WHERE id = 1                                          | PROMPT-SQL-NOT-SELECT
            UPDATE orders SET quantity = 99                                          | PROMPT-SQL-NOT-SELECT
            INSERT INTO orders (id) VALUES (1)                                       | PROMPT-SQL-NOT-SELECT
            SELECT id FROM orders; DROP TABLE orders                                 | PROMPT-SQL-MULTI
            SELECT id FROM orders UNION SELECT id FROM users                         | PROMPT-SQL-NOT-SELECT
            SELECT password_hash FROM users                                          | PROMPT-SQL-TABLE
            SELECT shipping_address FROM orders                                      | PROMPT-SQL-COLUMN
            SELECT id FROM orders o                                                  | PROMPT-SQL-TABLE
            SELECT id FROM public.orders                                             | PROMPT-SQL-TABLE
            SELECT orders.id, users.email FROM orders, users                         | PROMPT-SQL-CLAUSE
            SELECT id FROM orders JOIN users ON users.id = orders.user_id            | PROMPT-SQL-CLAUSE
            WITH x AS (SELECT * FROM users) SELECT id FROM orders                    | PROMPT-SQL-CLAUSE
            SELECT id FROM orders WHERE id IN (SELECT id FROM orders)                | PROMPT-SQL-EXPR
            SELECT id FROM orders WHERE EXISTS (SELECT 1 FROM users)                 | PROMPT-SQL-EXPR
            SELECT id FROM orders WHERE quantity = (SELECT MAX(quantity) FROM orders) | PROMPT-SQL-EXPR
            SELECT LOWER(note) FROM orders                                           | PROMPT-SQL-EXPR
            SELECT id FROM orders WHERE LENGTH(note) > 3                             | PROMPT-SQL-EXPR
            SELECT id FROM orders WHERE 1 = 1                                        | PROMPT-SQL-EXPR
            SELECT id FROM orders WHERE quantity = quantity                          | PROMPT-SQL-EXPR
            SELECT id AS x FROM orders                                               | PROMPT-SQL-CLAUSE
            SELECT DISTINCT note FROM orders                                         | PROMPT-SQL-CLAUSE
            SELECT id FROM orders GROUP BY id                                        | PROMPT-SQL-CLAUSE
            SELECT id FROM orders FOR UPDATE                                         | PROMPT-SQL-CLAUSE
            SELECT id FROM orders LIMIT 10 OFFSET 5                                  | PROMPT-SQL-CLAUSE
            SELECT id INTO copy FROM orders                                          | PROMPT-SQL-CLAUSE
            SELECT id FROM orders WHERE note = 'a' -- AND user_id = 1                | ok
            this is not sql                                                          | PROMPT-SQL-PARSE
            """)
    void attacksAreBlockedWithTheRightRule(String sql, String rule) {
        if (rule.equals("ok")) {
            // 주석은 파서가 버린다: 관문이 다시 만든 SQL에는 남지 않는다
            assertFalse(allowed(sql).sql().contains("--"));
            return;
        }
        assertEquals(rule, blockedRule(sql));
    }

    @Test
    void currentUserIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> GUARD.check("SELECT id FROM orders", " "));
    }

    @Test
    void blockedResultKeepsTheEvidence() {
        var b = GUARD.check("SELECT password_hash FROM users", ALICE).rejection().orElseThrow();
        assertTrue(b.reason().contains("orders"), b.reason());
        assertTrue(b.snippet().contains("users"), b.snippet());
    }
}
