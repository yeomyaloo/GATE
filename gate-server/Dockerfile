# syntax=docker/dockerfile:1
# 베이스 이미지는 태그 + digest로 고정한다 (재현 가능한 빌드, 공급망 변조 방지).
# 빌드 컨텍스트는 저장소 루트다 (docker-compose.yml 참고).

FROM eclipse-temurin:17.0.21_9-jdk-alpine-3.24@sha256:efefbce46b0d79073a3a676d91ac6c4bf370d348af18e388bbf7482543d7866a AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY gate-common ./gate-common
COPY gate-server ./gate-server
# settings.gradle.kts에 포함된 다른 모듈은 폴더가 있어야 Gradle이 설정 단계를 통과한다.
# 서버 이미지에 필요 없는 소스는 넣지 않고 빌드 파일만 복사한다. (모듈을 추가하면 여기에도 한 줄 추가)
COPY gate-pkg/build.gradle.kts ./gate-pkg/
COPY gate-lab/build.gradle.kts ./gate-lab/
RUN chmod +x gradlew && ./gradlew --no-daemon :gate-server:bootJar -x test

FROM eclipse-temurin:17.0.21_9-jre-alpine-3.24@sha256:e2ba4e947bd5e236a9866fbdda03e4936036cb9e9d16ea0d84d103d6634a5c21
RUN addgroup -S gate && adduser -S gate -G gate
WORKDIR /app
COPY --from=build /workspace/gate-server/build/libs/gate-server.jar app.jar
USER gate
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
