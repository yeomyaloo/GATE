// ② gate-review: PR 변경분을 정적 분석(Semgrep)과 AI로 함께 검토한다. AI 단독으로는 합격·불합격을 내리지 않는다.
// Semgrep은 외부 프로그램으로 실행한다(버전 고정, semgrep/requirements.txt). 2주차에 공식 Anthropic Java SDK를 추가한다.

dependencies {
    implementation(project(":gate-common"))
    implementation(project(":gate-pkg")) // git 변경 파일 읽기(GitReader) 재사용
    implementation(platform(libs.spring.boot.bom))
    implementation("tools.jackson.core:jackson-databind")

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 실제 semgrep을 부르는 테스트는 @Tag("semgrep")로 분리한다. 로컬에 semgrep이 없으면 건너뛰고, CI에서는 반드시 실행한다.
tasks.named<Test>("test") {
    systemProperty("gate.semgrep", System.getenv("GATE_SEMGREP") ?: "semgrep")
}
