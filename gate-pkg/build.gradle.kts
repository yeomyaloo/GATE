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
