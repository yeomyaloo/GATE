// 모든 모듈이 공유하는 검사 결과 모델. 런타임 외부 의존성 없이 순수 Java로 유지한다.
dependencies {
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
