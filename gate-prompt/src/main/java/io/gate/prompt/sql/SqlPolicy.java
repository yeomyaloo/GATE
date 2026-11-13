package io.gate.prompt.sql;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI가 만든 SQL에 허용하는 범위.
 *
 * @param table       읽을 수 있는 테이블 하나
 * @param columns     조회·조건·정렬에 쓸 수 있는 열 (이외의 열은 막는다)
 * @param ownerColumn 사용자 범위 열. 관문이 {@code ownerColumn = 현재 사용자} 조건을 항상 덧붙인다
 *                    (이 열은 조회 결과로 돌려주지 않아도 된다: columns에 없으면 조건에만 쓰인다)
 * @param maxRows     결과 행 수 상한 (AI가 더 큰 LIMIT을 써도 이 값으로 줄인다)
 */
public record SqlPolicy(String table, List<String> columns, String ownerColumn, int maxRows) {

    public SqlPolicy {
        if (table == null || !table.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("table must be a plain identifier");
        }
        if (ownerColumn == null || !ownerColumn.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("ownerColumn must be a plain identifier");
        }
        columns = List.copyOf(columns);
        if (columns.isEmpty() || !columns.stream().allMatch(c -> c.matches("[A-Za-z_][A-Za-z0-9_]*"))) {
            throw new IllegalArgumentException("columns must be plain identifiers");
        }
        if (maxRows < 1 || maxRows > 1000) {
            throw new IllegalArgumentException("maxRows must be 1..1000");
        }
    }

    Set<String> columnSet() {
        return columns.stream().map(c -> c.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }
}
