// ⑤ gate-lab: 일부러 취약하게 만든 실습용 앱 (외부 공개 배포 금지). 뚫린 버전·고친 버전을 프로필로 나눈다.
plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
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

tasks.named<Jar>("jar") {
    enabled = false
}
