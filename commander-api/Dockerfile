# Multi-stage build for the Incident Commander.
#
# The layer order is the point: build descriptors first, then dependency resolution, then source.
# Editing an agent prompt rebuilds the last layer only. Editing a POM rebuilds the world, which is
# correct — a changed dependency graph is exactly the case where a cached resolution would be a lie.
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /build

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
RUN ./mvnw -B -q -pl commander-api -am dependency:go-offline -DskipTests || true

COPY commander-domain/src commander-domain/src
COPY commander-application/src commander-application/src
COPY commander-adk/src commander-adk/src
COPY commander-persistence-postgres/src commander-persistence-postgres/src
COPY commander-integrations-aws/src commander-integrations-aws/src
COPY commander-simulator/src commander-simulator/src
COPY commander-testing/src commander-testing/src
COPY commander-api/src commander-api/src

# maven.test.skip, not skipTests. The first skips compiling the tests as well as running them; the
# second compiles them and then does not run them, which means this image build fails on a test it
# is never going to execute. Every module in the reactor is copied above regardless, so the build
# resolves without reaching for a published snapshot.
#
# The tests themselves run in CI, against the same commit, with the Docker daemon that Testcontainers
# needs and this build does not have. A Dockerfile that skips tests and a pipeline that does not run
# them are different problems; only the second one is real.
RUN ./mvnw -B -pl commander-api -am package -Dmaven.test.skip=true

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# wget is used by the ECS container health check. It is busybox's, already present in the base
# image, and named here so that a future base-image change that drops it is a conscious decision.
RUN addgroup -S commander && adduser -S commander -G commander
USER commander

COPY --from=build --chown=commander:commander /build/commander-api/target/*.jar app.jar

EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: the task's memory limit is set in the ECS task
# definition and in docker-compose, and a heap size compiled into the image would silently disagree
# with whichever of them changed last.
#
# ExitOnOutOfMemoryError is deliberate. A JVM limping along after an OOM is worse here than one
# that dies: incident state is durable in PostgreSQL and ADK sessions are resumable, so a restarted
# task picks the investigation back up, while a degraded one keeps holding an approval nobody can
# action.
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "/app/app.jar"]
