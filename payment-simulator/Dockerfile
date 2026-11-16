# syntax=docker/dockerfile:1
# Payment simulator image. Build from the payment-simulator folder:
#   docker build -t finbridge-simulator .

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
# The Maven repository is kept in a build cache between builds, so dependencies are
# downloaded once per machine, not once per build.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -DskipTests package \
    && cp target/payment-simulator-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S simulator && adduser -S -G simulator simulator
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER simulator
EXPOSE 8090
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    SIM_PORT=8090
# The WSDL is public and needs no key, so it serves as the health probe.
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1:${SIM_PORT}/ws/payments.wsdl || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
