// gate-license: 상업 라이선스 키 발급·확인, 사용 정보(opt-in) 설정. 외부 라이브러리 없음 (JDK의 Ed25519만 사용).
// 서명 개인 키는 저장소 밖 파일에 둔다. 이 모듈은 키 파일 경로를 받기만 한다.

dependencies {
    implementation(project(":gate-common"))

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 실행 가능한 단일 jar: build/libs/gate-license.jar
val cliJar by tasks.registering(Jar::class) {
    description = "Builds the runnable gate-license.jar including dependencies."
    group = "build"
    archiveFileName.set("gate-license.jar")
    manifest {
        attributes(
            "Main-Class" to "io.gate.license.GateLicenseCli",
            "Implementation-Title" to "gate-license",
            "Implementation-Version" to project.version,
        )
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
