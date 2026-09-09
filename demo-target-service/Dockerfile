# Multi-stage build for the demo target service.
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /build

# Copy the build descriptors first so dependency resolution is cached independently of source
# changes; editing a controller then does not re-download the world.
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
COPY build-tools build-tools
COPY commander-domain/pom.xml commander-domain/
COPY commander-application/pom.xml commander-application/
COPY commander-adk/pom.xml commander-adk/
COPY commander-persistence-postgres/pom.xml commander-persistence-postgres/
COPY commander-integrations-aws/pom.xml commander-integrations-aws/
COPY commander-simulator/pom.xml commander-simulator/
COPY commander-testing/pom.xml commander-testing/
COPY commander-api/pom.xml commander-api/
COPY demo-target-service/pom.xml demo-target-service/
RUN ./mvnw -B -q -pl demo-target-service -am dependency:go-offline -DskipTests || true

COPY demo-target-service/src demo-target-service/src
RUN ./mvnw -B -pl demo-target-service -am package -DskipTests

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Runs as a non-root user. This service deliberately misbehaves on request, so it gets the least
# privilege the runtime allows.
RUN addgroup -S demo && adduser -S demo -G demo
USER demo

COPY --from=build --chown=demo:demo /build/demo-target-service/target/*.jar app.jar

EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
