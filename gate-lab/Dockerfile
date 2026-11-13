# syntax=docker/dockerfile:1
# ⚠️ gate-lab: 일부러 취약한 실습용 앱. 이 이미지를 레지스트리에 올리거나 외부에 노출하지 않는다.
# 빌드 컨텍스트는 저장소 루트다 (docker-compose.yml의 lab 프로필 참고). 베이스 이미지는 태그 + digest로 고정.

FROM eclipse-temurin:17.0.21_9-jdk-alpine-3.24@sha256:efefbce46b0d79073a3a676d91ac6c4bf370d348af18e388bbf7482543d7866a AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY gate-lab ./gate-lab
# settings.gradle.kts에 포함된 다른 모듈은 폴더가 있어야 Gradle 설정 단계를 통과한다 (빌드 파일만 복사)
COPY gate-common/build.gradle.kts ./gate-common/
COPY gate-server/build.gradle.kts ./gate-server/
COPY gate-pkg/build.gradle.kts ./gate-pkg/
COPY gate-authz/build.gradle.kts ./gate-authz/
COPY gate-review/build.gradle.kts ./gate-review/
COPY gate-prompt/build.gradle.kts ./gate-prompt/
RUN chmod +x gradlew && ./gradlew --no-daemon :gate-lab:bootJar -x test

FROM eclipse-temurin:17.0.21_9-jre-alpine-3.24@sha256:e2ba4e947bd5e236a9866fbdda03e4936036cb9e9d16ea0d84d103d6634a5c21
RUN addgroup -S lab && adduser -S lab -G lab
WORKDIR /app
COPY --from=build /workspace/gate-lab/build/libs/gate-lab.jar app.jar
USER lab
# 컨테이너 안에서만 0.0.0.0에 묶는다. 안전장치가 /.dockerenv와 GATE_LAB_IN_CONTAINER=true를 확인한다.
# 호스트에는 compose가 127.0.0.1에만 포트를 연다.
ENV SERVER_ADDRESS=0.0.0.0 \
    GATE_LAB_IN_CONTAINER=true
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
