package io.gate.prompt.sql;

import io.gate.prompt.GuardResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;

/**
 * AI가 만든 SQL을 실행하기 전의 관문 (설계안 2·3장).
 *
 * <p>AI 출력은 실행하지 않는다. 파서로 구조를 읽고, <b>허용 목록에 있는 부분만 골라 관문이 SQL을 새로 만든다.</b>
 * <ul>
 *   <li>문장 하나, 단순 SELECT 하나만 (UNION·WITH·하위 질의·JOIN·INTO·FOR UPDATE 등은 차단)</li>
 *   <li>정책의 테이블 하나, 정책의 열만. 함수·형 변환·연산식은 차단 (조건은 열과 상수의 비교만)</li>
 *   <li>상수는 모두 바인딩 변수(?)로 바꾼다: 따옴표 탈출 같은 문자열 기법이 통하지 않는다</li>
 *   <li>{@code ownerColumn = ?}(현재 사용자)를 항상 AND로 덧붙인다: AI가 쓴 조건이 무엇이든 남의 행은 보이지 않는다</li>
 *   <li>행 수는 정책 상한 이하로</li>
 * </ul>
 */
public final class SqlGuard {

    public static final String PARSE = "PROMPT-SQL-PARSE";
    public static final String MULTI = "PROMPT-SQL-MULTI";
    public static final String NOT_SELECT = "PROMPT-SQL-NOT-SELECT";
    public static final String CLAUSE = "PROMPT-SQL-CLAUSE";
    public static final String TABLE = "PROMPT-SQL-TABLE";
    public static final String COLUMN = "PROMPT-SQL-COLUMN";
    public static final String EXPRESSION = "PROMPT-SQL-EXPR";

    /** 관문이 다시 만든 질의. {@code sql}은 관문이 직접 조립한 문자열이고 값은 모두 {@code params}에 있다. */
    public record BoundQuery(String sql, List<Object> params, List<String> columns) {
        public BoundQuery {
            params = List.copyOf(params);
            columns = List.copyOf(columns);
        }
    }

    private final SqlPolicy policy;

    public SqlGuard(SqlPolicy policy) {
        this.policy = policy;
    }

    /** @param currentUser 사용자 범위 열과 비교할 값 (예: 로그인한 사용자 ID). 바인딩 변수로만 쓰인다 */
    public GuardResult<BoundQuery> check(String aiSql, Object currentUser) {
        if (currentUser == null || currentUser.toString().isBlank()) {
            throw new IllegalArgumentException("current user is required");
        }
        if (aiSql == null || aiSql.isBlank()) {
            return GuardResult.block(PARSE, "empty SQL", "");
        }
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(aiSql.strip());
        } catch (JSQLParserException | RuntimeException e) {
            return GuardResult.block(PARSE, "SQL could not be parsed", aiSql);
        }
        if (statements.size() != 1) {
            return GuardResult.block(MULTI, "exactly one statement is allowed (found " + statements.size() + ")",
                    aiSql);
        }
        Statement st = statements.get(0);
        if (!(st instanceof PlainSelect select)) {
            return GuardResult.block(NOT_SELECT, "only a plain SELECT is allowed (found "
                    + st.getClass().getSimpleName() + ")", st.toString());
        }
        try {
            return GuardResult.allow(rebuild(select, currentUser));
        } catch (Violation v) {
            return GuardResult.block(v.ruleId, v.getMessage(), v.snippet);
        }
    }

    private BoundQuery rebuild(PlainSelect s, Object user) {
        clauses(s);
        if (!(s.getFromItem() instanceof Table t) || t.getAlias() != null || t.getSchemaName() != null
                || !t.getUnquotedName().equalsIgnoreCase(policy.table())) {
            throw new Violation(TABLE, "only table '" + policy.table() + "' may be read",
                    String.valueOf(s.getFromItem()));
        }

        List<String> columns = new ArrayList<>();
        for (SelectItem<?> item : s.getSelectItems()) {
            if (item.getAlias() != null) {
                throw new Violation(CLAUSE, "aliases are not allowed", item.toString());
            }
            Expression e = item.getExpression();
            if (e instanceof AllColumns all && all.getExceptColumns() == null && all.getReplaceExpressions() == null
                    && !(e instanceof net.sf.jsqlparser.statement.select.AllTableColumns)) {
                for (String c : policy.columns()) {
                    if (!columns.contains(c)) {
                        columns.add(c);
                    }
                }
            } else if (e instanceof Column c) {
                String name = column(c);
                if (!columns.contains(name)) {
                    columns.add(name);
                }
            } else {
                throw new Violation(EXPRESSION, "only plain columns may be selected", String.valueOf(e));
            }
        }

        List<Object> params = new ArrayList<>();
        params.add(user);
        StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", columns)).append(" FROM ")
                .append(policy.table()).append(" WHERE ").append(policy.ownerColumn()).append(" = ?");
        if (s.getWhere() != null) {
            sql.append(" AND (").append(condition(s.getWhere(), params)).append(')');
        }
        if (s.getOrderByElements() != null && !s.getOrderByElements().isEmpty()) {
            List<String> order = new ArrayList<>();
            for (OrderByElement o : s.getOrderByElements()) {
                if (!(o.getExpression() instanceof Column c) || o.getNullOrdering() != null
                        || o.getWithFill() != null) {
                    throw new Violation(EXPRESSION, "ORDER BY may only use plain columns", o.toString());
                }
                order.add(column(c) + (o.isAsc() ? " ASC" : " DESC"));
            }
            sql.append(" ORDER BY ").append(String.join(", ", order));
        }
        sql.append(" LIMIT ").append(limit(s));
        return new BoundQuery(sql.toString(), params, columns);
    }

    /** 단순 SELECT 밖의 절은 모두 막는다 (다시 만들 때 버려지지만, 막은 이유를 남기려고 명시적으로 검사). */
    private static void clauses(PlainSelect s) {
        List<String> found = new ArrayList<>();
        if (s.getWithItemsList() != null && !s.getWithItemsList().isEmpty()) {
            found.add("WITH");
        }
        if (s.getJoins() != null && !s.getJoins().isEmpty()) {
            found.add("JOIN");
        }
        if (s.getIntoTables() != null || s.getIntoTempTable() != null || s.getMySqlSelectIntoClause() != null) {
            found.add("INTO");
        }
        if (s.getGroupBy() != null || s.getHaving() != null) {
            found.add("GROUP BY/HAVING");
        }
        if (s.getDistinct() != null || s.getTop() != null || s.getFirst() != null || s.getSkip() != null) {
            found.add("DISTINCT/TOP");
        }
        if (s.getForMode() != null || s.getForUpdateTable() != null || s.getForClause() != null) {
            found.add("FOR UPDATE");
        }
        if (s.getOffset() != null || s.getFetch() != null) {
            found.add("OFFSET/FETCH");
        }
        if (s.getLateralViews() != null && !s.getLateralViews().isEmpty() || s.getWindowDefinitions() != null
                || s.getQualify() != null || s.getPreWhere() != null || s.getOracleHierarchical() != null
                || s.getSampleClause() != null || s.getOption() != null || s.getIsolation() != null) {
            found.add("dialect-specific clause");
        }
        if (!found.isEmpty()) {
            throw new Violation(CLAUSE, "clause not allowed: " + String.join(", ", found), s.toString());
        }
    }

    private int limit(PlainSelect s) {
        if (s.getLimit() == null) {
            return policy.maxRows();
        }
        Expression rows = s.getLimit().getRowCount();
        if (s.getLimit().getOffset() != null || !(rows instanceof LongValue n) || n.getValue() < 1) {
            throw new Violation(CLAUSE, "LIMIT must be a positive number", s.getLimit().toString());
        }
        return (int) Math.min(n.getValue(), policy.maxRows());
    }

    private String column(Column c) {
        if (c.getTable() != null && c.getTable().getName() != null
                && !c.getTable().getUnquotedName().equalsIgnoreCase(policy.table())) {
            throw new Violation(TABLE, "column of another table: " + c, c.toString());
        }
        String name = c.getUnquotedColumnName().toLowerCase(Locale.ROOT);
        for (String allowed : policy.columns()) {
            if (allowed.toLowerCase(Locale.ROOT).equals(name)) {
                return allowed;
            }
        }
        if (policy.ownerColumn().equalsIgnoreCase(name)) {
            return policy.ownerColumn(); // 조건에는 쓸 수 있다 (어차피 현재 사용자로 고정된다)
        }
        throw new Violation(COLUMN, "column not allowed: " + c.getColumnName(), c.toString());
    }

    /** 조건식을 허용된 모양만으로 다시 쓴다. 상수는 ?로 바꿔 params에 넣는다. */
    private String condition(Expression e, List<Object> params) {
        if (e instanceof AndExpression a) {
            return condition(a.getLeftExpression(), params) + " AND " + condition(a.getRightExpression(), params);
        }
        if (e instanceof OrExpression o) {
            return condition(o.getLeftExpression(), params) + " OR " + condition(o.getRightExpression(), params);
        }
        if (e instanceof ParenthesedExpressionList<?> p && p.size() == 1) {
            return "(" + condition(p.get(0), params) + ")";
        }
        if (e instanceof NotExpression n) {
            String inner = condition(n.getExpression(), params);
            return n.getExpression() instanceof ParenthesedExpressionList<?> ? "NOT " + inner : "NOT (" + inner + ")";
        }
        if (e instanceof LikeExpression like) {
            if (like.getEscape() != null || like.getLikeKeyWord() != LikeExpression.KeyWord.LIKE
                    && like.getLikeKeyWord() != LikeExpression.KeyWord.ILIKE) {
                throw new Violation(EXPRESSION, "only LIKE/ILIKE without ESCAPE", e.toString());
            }
            return operand(like.getLeftExpression(), params, true) + (like.isNot() ? " NOT " : " ")
                    + like.getLikeKeyWord().name() + " " + operand(like.getRightExpression(), params, false);
        }
        if (e instanceof EqualsTo || e instanceof NotEqualsTo || e instanceof GreaterThan
                || e instanceof GreaterThanEquals || e instanceof MinorThan || e instanceof MinorThanEquals) {
            BinaryExpression b = (BinaryExpression) e;
            String op = e instanceof EqualsTo ? "=" : e instanceof NotEqualsTo ? "<>"
                    : e instanceof GreaterThanEquals ? ">=" : e instanceof GreaterThan ? ">"
                    : e instanceof MinorThanEquals ? "<=" : "<";
            boolean leftColumn = b.getLeftExpression() instanceof Column;
            return operand(b.getLeftExpression(), params, leftColumn) + " " + op + " "
                    + operand(b.getRightExpression(), params, !leftColumn);
        }
        if (e instanceof Between b) {
            if (b.isUsingSymmetric() || b.isUsingAsymmetric()) {
                throw new Violation(EXPRESSION, "only plain BETWEEN", e.toString());
            }
            return operand(b.getLeftExpression(), params, true) + (b.isNot() ? " NOT" : "") + " BETWEEN "
                    + operand(b.getBetweenExpressionStart(), params, false) + " AND "
                    + operand(b.getBetweenExpressionEnd(), params, false);
        }
        if (e instanceof InExpression in) {
            if (!(in.getRightExpression() instanceof ExpressionList<?> list) || list.isEmpty()) {
                throw new Violation(EXPRESSION, "IN needs a list of constants", e.toString());
            }
            List<String> items = new ArrayList<>();
            for (Expression item : list) {
                items.add(operand(item, params, false));
            }
            return operand(in.getLeftExpression(), params, true) + (in.isNot() ? " NOT" : "") + " IN ("
                    + String.join(", ", items) + ")";
        }
        if (e instanceof IsNullExpression n) {
            return operand(n.getLeftExpression(), params, true) + (n.isNot() ? " IS NOT NULL" : " IS NULL");
        }
        throw new Violation(EXPRESSION, "condition not allowed: " + e.getClass().getSimpleName(), e.toString());
    }

    /**
     * 비교의 한쪽. 열 자리에는 허용된 열만, 값 자리에는 상수만 (상수는 ?로).
     * 열끼리 비교({@code owner = owner})나 상수끼리 비교({@code 1 = 1})는 막지 않아도 안전하지만 단순하게 열 하나와 값 하나로 제한한다.
     */
    private String operand(Expression e, List<Object> params, boolean columnSide) {
        if (columnSide) {
            if (e instanceof Column c) {
                return column(c);
            }
            throw new Violation(EXPRESSION, "expected a column, found " + e.getClass().getSimpleName(),
                    String.valueOf(e));
        }
        Object value = constant(e);
        params.add(value);
        return "?";
    }

    private static Object constant(Expression e) {
        if (e instanceof StringValue s) {
            if (s.getPrefix() != null) {
                throw new Violation(EXPRESSION, "prefixed string literals are not allowed", e.toString());
            }
            return s.getValue().replace("''", "'");
        }
        if (e instanceof LongValue l) {
            return l.getValue();
        }
        if (e instanceof DoubleValue d) {
            return d.getValue();
        }
        if (e instanceof SignedExpression s && s.getSign() == '-' && (s.getExpression() instanceof LongValue
                || s.getExpression() instanceof DoubleValue)) {
            Object inner = constant(s.getExpression());
            return inner instanceof Long l ? -l : -((Double) inner);
        }
        if (e instanceof NullValue) {
            throw new Violation(EXPRESSION, "use IS NULL instead of comparing with NULL", e.toString());
        }
        throw new Violation(EXPRESSION, "expected a constant, found " + (e == null ? "nothing"
                : e.getClass().getSimpleName()), String.valueOf(e));
    }

    private static final class Violation extends RuntimeException {
        private final String ruleId;
        private final String snippet;

        Violation(String ruleId, String message, String snippet) {
            super(message, null, false, false);
            this.ruleId = ruleId;
            this.snippet = snippet;
        }
    }
}
