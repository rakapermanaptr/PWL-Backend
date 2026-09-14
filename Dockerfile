# syntax=docker/dockerfile:1

# ---- build ------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
RUN ./gradlew --version --no-daemon

COPY src src
COPY openapi.yaml ./
RUN ./gradlew installDist --no-daemon -x test

# ---- runtime ----------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN useradd --system --uid 10001 pwl
COPY --from=build /src/build/install/pwl-cashier ./
COPY --from=build /src/openapi.yaml ./

USER pwl
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=UTC"

HEALTHCHECK --interval=15s --timeout=3s --start-period=30s \
    CMD ["/bin/sh", "-c", "wget -qO- http://localhost:8080/health/ready || exit 1"]

ENTRYPOINT ["./bin/pwl-cashier"]
