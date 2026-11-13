// ⑤ gate-lab: 일부러 취약하게 만든 실습용 앱 (외부 공개 배포 금지). 뚫린 버전·고친 버전을 프로필로 나눈다.
plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    // 5단계: 주문 도우미(고친 버전)가 AI 출력 관문을 쓴다. 가짜 모델도 여기에 있다
    implementation(project(":gate-prompt"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("gate-lab.jar")
}

// 일반 jar(-plain)는 남겨 둔다: gate-authz 테스트가 실습장을 직접 띄우려고 이 모듈 클래스를 쓴다
tasks.named<Jar>("jar") {
    archiveClassifier.set("plain")
}
