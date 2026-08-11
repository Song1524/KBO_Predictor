FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace

COPY gradle ./gradle
COPY gradlew build.gradle settings.gradle ./
RUN chmod +x gradlew

COPY src ./src
COPY ml/artifacts/logistic-v1.json ./ml/artifacts/logistic-v1.json

RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S playball && adduser -S playball -G playball
WORKDIR /app

COPY --from=build --chown=playball:playball /workspace/build/libs/kbo-predictor-*.jar /app/app.jar

USER playball
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
