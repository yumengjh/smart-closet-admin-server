# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
ARG APP_VERSION=0.0.1-SNAPSHOT

RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests dependency:go-offline

COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests -Drevision="$APP_VERSION" package \
    && test -f target/application.jar \
    && cp target/application.jar /workspace/app.jar

FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app \
    && install -d -o app -g app /app/data
WORKDIR /app
COPY --from=build --chown=app:app /workspace/app.jar /app/app.jar

USER 10001:10001
ENV SERVER_PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD java -Dloader.main=com.yumg.starter.ContainerHealthCheck -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher
ENTRYPOINT ["java","-jar","/app/app.jar"]
