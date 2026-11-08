package io.gate.common.finding;

import java.util.Objects;

/**
 * 발견 위치("어디서"). 모듈마다 모양이 달라 DB에는 JSONB로 저장하고, 코드에서는 타입으로 구분한다.
 */
public sealed interface Location permits Location.Package, Location.File, Location.Endpoint {

    String kind();

    /** 패키지 레지스트리 위치. 예) npm:lodash@4.17.21 */
    record Package(String ecosystem, String name, String version) implements Location {
        public Package {
            requireText(ecosystem, "ecosystem");
            requireText(name, "name");
        }

        @Override
        public String kind() {
            return "package";
        }
    }

    /** 소스 코드 위치. line은 1부터 시작한다. */
    record File(String path, int line) implements Location {
        public File {
            requireText(path, "path");
            if (line < 1) {
                throw new IllegalArgumentException("line must be >= 1");
            }
        }

        @Override
        public String kind() {
            return "file";
        }
    }

    /** API 엔드포인트 위치. 예) GET /api/orders/{id} */
    record Endpoint(String method, String path) implements Location {
        public Endpoint {
            requireText(method, "method");
            requireText(path, "path");
            method = method.toUpperCase();
        }

        @Override
        public String kind() {
            return "endpoint";
        }
    }

    private static void requireText(String value, String field) {
        if (Objects.requireNonNull(value, field + " must not be null").isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
