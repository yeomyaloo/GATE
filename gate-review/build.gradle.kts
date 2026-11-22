// ② gate-review: PR 변경분을 정적 분석(Semgrep)과 AI로 함께 검토한다. AI 단독으로는 합격·불합격을 내리지 않는다.
// Semgrep은 외부 프로그램으로 실행한다(버전 고정, semgrep/requirements.txt). AI는 공식 Anthropic Java SDK로 호출한다.

dependencies {
    implementation(project(":gate-common"))
    implementation(project(":gate-pkg")) // git 변경 파일 읽기(GitReader) 재사용
    implementation(platform(libs.spring.boot.bom))
    implementation("tools.jackson.core:jackson-databind")
    // 공식 Anthropic Java SDK (2026-10-23 공개 3.3.0으로 고정: 2주 이상 된 최신판, 공식 그룹 com.anthropic)
    implementation("com.anthropic:anthropic-java:3.3.0")
    // SDK가 끌고 오는 opentelemetry-api를 Spring Boot BOM이 1.62.0으로 고정한다. 그 버전에는 알려진 취약점이 있어
    // (GHSA-ccrj-gw8r-854m / CVE-2026-118259, W3C tracestate 파싱 CPU 과다, 1.67.0에서 수정) OpenTelemetry BOM으로 올린다.
    // 1.68.0: 2026-11-06 공개(2주 이상), OSV 알려진 취약점 없음. 이 CLI는 들어오는 헤더를 파싱하지 않아 실제로 닿지 않지만 올린다
    implementation(platform("io.opentelemetry:opentelemetry-bom:1.68.0"))

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 실제 semgrep을 부르는 테스트는 @Tag("semgrep")로 분리한다. 로컬에 semgrep이 없으면 건너뛰고, CI에서는 반드시 실행한다.
tasks.named<Test>("test") {
    systemProperty("gate.semgrep", System.getenv("GATE_SEMGREP") ?: "semgrep")
}

// 실행 가능한 단일 jar: build/libs/gate-review.jar (의존성 포함). 3주차 PR 워크플로가 실행한다.
val cliJar by tasks.registering(Jar::class) {
    description = "Builds the runnable gate-review.jar including dependencies."
    group = "build"
    archiveFileName.set("gate-review.jar")
    manifest {
        attributes(
            "Main-Class" to "io.gate.review.cli.GateReviewCli",
            "Multi-Release" to "true",
            "Implementation-Title" to "gate-review",
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
