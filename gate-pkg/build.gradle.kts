// ① gate-pkg: 패키지 설치 검문소. Spring 없이 순수 Java로 만든 CLI.
// 외부 라이브러리는 Jackson 하나이며, 버전은 Spring Boot BOM으로 고정한다.

dependencies {
    implementation(project(":gate-common"))
    implementation(platform(libs.spring.boot.bom))
    implementation("tools.jackson.core:jackson-databind")

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 기본 테스트는 네트워크를 쓰지 않는다. 실제 레지스트리를 호출하는 테스트는 @Tag("live")로 분리한다.
tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("live")
    }
}

tasks.register<Test>("liveTest") {
    description = "Runs tests that call real package registries (network required)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("live")
    }
}

// 실행 가능한 단일 jar: build/libs/gate-pkg.jar (의존성 포함). 래퍼 스크립트 bin/gate-pkg*가 이 파일을 실행한다.
val cliJar by tasks.registering(Jar::class) {
    description = "Builds the runnable gate-pkg.jar including dependencies."
    group = "build"
    archiveFileName.set("gate-pkg.jar")
    manifest {
        attributes(
            "Main-Class" to "io.gate.pkg.cli.GatePkgCli",
            "Multi-Release" to "true",
            "Implementation-Title" to "gate-pkg",
            "Implementation-Version" to project.version,
        )
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC", "module-info.class",
        "META-INF/versions/*/module-info.class")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named("assemble") {
    dependsOn(cliJar)
}
