# syntax=docker/dockerfile:1
# FinBridge backend image. Build from the backend folder:
#   docker build -t finbridge-backend .
#
# Two stages: Maven and a JDK build the jar; only a JRE and the jar go into the final image.
# Tests are not run here. They need Docker themselves (Testcontainers), so they run before
# the image is built: "mvn verify" first, then "docker build".

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
# The Maven repository is kept in a build cache between builds, so dependencies are
# downloaded once per machine, not once per build.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -DskipTests -Djacoco.skip=true package \
    && cp target/finbridge-backend-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-alpine
# No root inside the container.
RUN addgroup -S finbridge && adduser -S -G finbridge finbridge
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER finbridge
EXPOSE 8080
# Use at most three quarters of the container's memory for the heap, and fail fast
# (so the orchestrator restarts the task) instead of limping on after an OutOfMemoryError.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    SERVER_PORT=8080
# No secret and no profile is set here: both come from the environment that runs the image.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1:${SERVER_PORT}/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
