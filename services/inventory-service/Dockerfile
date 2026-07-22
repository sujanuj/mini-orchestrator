# Multi-stage build: the build stage has the full JDK + Maven and
# downloads dependencies; the final image only has a JRE and the built
# jar. This keeps the shipped image meaningfully smaller and avoids
# shipping build tooling into what's supposed to be a runtime artifact.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Copy pom.xml first and run a dependency-only step so Docker's layer
# cache can skip re-downloading dependencies on every rebuild when only
# source files (not pom.xml) have changed.
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/target/inventory-service.jar app.jar

EXPOSE 8080

# Used by the orchestrator's health checker (Phase 2+) as the reference
# for what "healthy" means for this container -- matches
# InventoryController's /health endpoint exactly.
HEALTHCHECK --interval=5s --timeout=3s --start-period=15s --retries=3 \
  CMD wget -q -O- http://localhost:8080/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
