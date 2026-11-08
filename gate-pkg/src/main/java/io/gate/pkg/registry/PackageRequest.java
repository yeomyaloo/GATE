package io.gate.pkg.registry;

import java.util.Objects;

/**
 * 설치하려는 패키지 하나. {@code versionSpec}은 사용자가 적은 그대로이며 없으면 null이다
 * (예: {@code 4.17.21}, {@code ^4}, {@code latest}).
 */
public record PackageRequest(Ecosystem ecosystem, String name, String versionSpec) {

    public PackageRequest {
        Objects.requireNonNull(ecosystem, "ecosystem");
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (versionSpec != null && versionSpec.isBlank()) {
            versionSpec = null;
        }
    }

    public static PackageRequest npm(String name, String versionSpec) {
        return new PackageRequest(Ecosystem.NPM, name, versionSpec);
    }

    @Override
    public String toString() {
        return ecosystem.id() + ":" + name + (versionSpec == null ? "" : "@" + versionSpec);
    }
}
