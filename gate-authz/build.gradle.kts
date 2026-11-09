// ③ gate-authz: OpenAPI를 읽고 사용자 A의 토큰으로 B의 객체 접근을 자동 시도한다 (BOLA·인증 누락).
// 순수 Java CLI. 외부 라이브러리는 Jackson(JSON·YAML)뿐이며 버전은 Spring Boot BOM으로 고정한다.

dependencies {
    implementation(project(":gate-common"))
    implementation(platform(libs.spring.boot.bom))
    implementation("tools.jackson.core:jackson-databind")
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // 완료 기준 테스트: 실습장(gate-lab)을 테스트 안에서 직접 띄워 두 버전을 검사한다 (Docker 불필요)
    testImplementation(project(":gate-lab"))
    testImplementation("org.springframework.boot:spring-boot")
}

// 실행 가능한 단일 jar: build/libs/gate-authz.jar (의존성 포함). 래퍼 스크립트 bin/gate-authz*가 실행한다.
val cliJar by tasks.registering(Jar::class) {
    description = "Builds the runnable gate-authz.jar including dependencies."
    group = "build"
    archiveFileName.set("gate-authz.jar")
    manifest {
        attributes(
            "Main-Class" to "io.gate.authz.cli.GateAuthzCli",
            "Multi-Release" to "true",
            "Implementation-Title" to "gate-authz",
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
