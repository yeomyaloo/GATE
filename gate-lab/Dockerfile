# syntax=docker/dockerfile:1
# ⚠️ gate-lab: 일부러 취약한 실습용 앱. 이 이미지를 레지스트리에 올리거나 외부에 노출하지 않는다.
# 빌드 컨텍스트는 저장소 루트다 (docker-compose.yml의 lab 프로필 참고). 베이스 이미지는 태그 + digest로 고정.

FROM eclipse-temurin:17-jdk-alpine-3.21@sha256:da900be8c9c4fbcc8273e345acce8a5f5c01bf816ec8fbac5f04b2a51af5631d AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY gate-lab ./gate-lab
# settings.gradle.kts에 포함된 다른 모듈은 폴더가 있어야 Gradle 설정 단계를 통과한다 (빌드 파일만 복사)
COPY gate-common ./gate-common
COPY gate-prompt ./gate-prompt
COPY gate-server/build.gradle.kts ./gate-server/
COPY gate-pkg/build.gradle.kts ./gate-pkg/
COPY gate-authz/build.gradle.kts ./gate-authz/
COPY gate-review/build.gradle.kts ./gate-review/
COPY gate-license/build.gradle.kts ./gate-license/
RUN chmod +x gradlew && ./gradlew --no-daemon :gate-lab:bootJar -x test

FROM eclipse-temurin:17-jre-alpine-3.21@sha256:3a31efca6e13f64e3b395941be5e2fac8ff968cfd71c7223fd8ab13617fc90c7
RUN addgroup -S lab && adduser -S lab -G lab
WORKDIR /app
COPY --from=build /workspace/gate-lab/build/libs/gate-lab.jar app.jar
USER lab
# 컨테이너 안에서만 0.0.0.0에 묶는다. 안전장치가 /.dockerenv와 GATE_LAB_IN_CONTAINER=true를 확인한다.
# 호스트에는 compose가 127.0.0.1에만 포트를 연다.
ENV SERVER_ADDRESS=0.0.0.0 \
    GATE_LAB_IN_CONTAINER=true
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
