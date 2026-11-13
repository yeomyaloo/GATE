// ④ gate-prompt: AI 기능의 입력·출력 관문. AI 출력이 SQL·HTML·동작으로 실행되기 전에 허용 목록으로 검사한다.
// 순수 Java 라이브러리. 외부 라이브러리: JSqlParser(SQL 구조 분석), OWASP Java HTML Sanitizer(HTML 허용 목록), Jackson(BOM).

dependencies {
    implementation(project(":gate-common"))
    implementation(platform(libs.spring.boot.bom))
    implementation("tools.jackson.core:jackson-databind")
    implementation(libs.jsqlparser)
    implementation(libs.owasp.html.sanitizer)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
