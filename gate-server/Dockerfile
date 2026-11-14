# syntax=docker/dockerfile:1
# 베이스 이미지는 태그 + digest로 고정한다 (재현 가능한 빌드, 공급망 변조 방지).
# 빌드 컨텍스트는 저장소 루트다 (docker-compose.yml 참고).

# 1) 대시보드 화면 빌드 (node:24.22.2-alpine3.24). lock 파일 그대로 설치(npm ci), 설치 스크립트는 실행하지 않는다(.npmrc)
FROM node@sha256:a34ab6d7f6f4813f4d0c5469d74685604b962fbdbf5793a7bc7b4998ecb867c8 AS dashboard
WORKDIR /dashboard
COPY dashboard/package.json dashboard/package-lock.json dashboard/.npmrc ./
RUN npm ci
COPY dashboard/index.html dashboard/tsconfig.json dashboard/vite.config.ts ./
COPY dashboard/src ./src
RUN npm run build

# 2) 서버 빌드. 화면 빌드 결과를 정적 파일로 넣어 gate-server가 같은 주소에서 제공한다 (설계안 결정 1)
FROM eclipse-temurin:17.0.21_9-jdk-alpine-3.24@sha256:efefbce46b0d79073a3a676d91ac6c4bf370d348af18e388bbf7482543d7866a AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY gate-common ./gate-common
COPY gate-server ./gate-server
COPY --from=dashboard /dashboard/dist ./gate-server/src/main/resources/static
# settings.gradle.kts에 포함된 다른 모듈은 폴더가 있어야 Gradle이 설정 단계를 통과한다.
# 서버 이미지에 필요 없는 소스는 넣지 않고 빌드 파일만 복사한다. (모듈을 추가하면 여기에도 한 줄 추가)
COPY gate-pkg/build.gradle.kts ./gate-pkg/
COPY gate-lab/build.gradle.kts ./gate-lab/
COPY gate-authz/build.gradle.kts ./gate-authz/
COPY gate-review/build.gradle.kts ./gate-review/
COPY gate-prompt/build.gradle.kts ./gate-prompt/
RUN chmod +x gradlew && ./gradlew --no-daemon :gate-server:bootJar -x test

FROM eclipse-temurin:17.0.21_9-jre-alpine-3.24@sha256:e2ba4e947bd5e236a9866fbdda03e4936036cb9e9d16ea0d84d103d6634a5c21
RUN addgroup -S gate && adduser -S gate -G gate
WORKDIR /app
COPY --from=build /workspace/gate-server/build/libs/gate-server.jar app.jar
USER gate
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
