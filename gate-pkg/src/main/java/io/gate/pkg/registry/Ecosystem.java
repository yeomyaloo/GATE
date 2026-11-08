package io.gate.pkg.registry;

/** 지원하는 패키지 생태계. {@code id}는 결과의 위치(location.ecosystem)에 그대로 쓴다. */
public enum Ecosystem {
    NPM("npm", "npm"),
    PYPI("pypi", "PyPI");

    private final String id;
    private final String osvName;

    Ecosystem(String id, String osvName) {
        this.id = id;
        this.osvName = osvName;
    }

    public String id() {
        return id;
    }

    /** OSV API가 쓰는 생태계 이름. */
    public String osvName() {
        return osvName;
    }
}
