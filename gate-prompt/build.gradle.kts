// ④ gate-prompt: AI 기능의 입력·출력 관문. AI 출력이 SQL·HTML·동작으로 실행되기 전에 허용 목록으로 검사한다.
// 순수 Java 라이브러리. 외부 라이브러리: JSqlParser(SQL 구조 분석), OWASP Java HTML Sanitizer(HTML 허용 목록), Jackson·H2(BOM).

dependencies {
    implementation(project(":gate-common"))
    implementation(platform(libs.spring.boot.bom))
    implementation("tools.jackson.core:jackson-databind")
    implementation(libs.jsqlparser)
    implementation(libs.owasp.html.sanitizer)
    // 측정(measure) 전용: 실습장과 같은 구조의 메모리 DB에서 AI 출력을 실제로 실행해 피해 여부를 확인한다 (BOM 버전, 실습장과 동일)
    implementation("com.h2database:h2")

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 실행 가능한 단일 jar: build/libs/gate-prompt.jar (의존성 포함). gate-prompt measure를 실행한다.
val cliJar by tasks.registering(Jar::class) {
    description = "Builds the runnable gate-prompt.jar including dependencies."
    group = "build"
    archiveFileName.set("gate-prompt.jar")
    manifest {
        attributes(
            "Main-Class" to "io.gate.prompt.cli.GatePromptCli",
            "Multi-Release" to "true",
            "Implementation-Title" to "gate-prompt",
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
