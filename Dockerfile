FROM gradle:8.7-jdk21 AS build
WORKDIR /app

# Gradle 캐시를 활용하기 위해 의존성 파일만 먼저 복사
COPY build.gradle settings.gradle ./
RUN gradle build -x test --no-daemon > /dev/null 2>&1 || true

COPY . .

RUN --mount=type=secret,id=GPR_USER \
    --mount=type=secret,id=GPR_TOKEN \
    export GPR_USER=$(cat /run/secrets/GPR_USER) && \
    export GPR_TOKEN=$(cat /run/secrets/GPR_TOKEN) && \
    gradle bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]