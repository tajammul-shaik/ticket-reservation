# Multi-stage build (BRD OPS-01): a JDK image compiles and packages the jar, and only the
# result is copied into a smaller JRE image. Maven, sources and build caches never reach
# the runtime image.

# ---- Stage 1: build ---------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copy only what Maven needs to resolve dependencies first. This layer is cached and reused
# until pom.xml changes, so editing Java code does not re-download every dependency.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

# Now the sources; tests run in CI/locally with Testcontainers, not inside the image build.
COPY src/ src/
RUN ./mvnw -B -q package -DskipTests

# Split the fat jar into layers (dependencies change rarely, application code often) so
# image layers for dependencies are reused across builds and pushes.
RUN java -Djarmode=tools -jar target/ticket-reservation-*.jar extract --layers --launcher --destination extracted

# ---- Stage 2: run -----------------------------------------------------------------------
FROM eclipse-temurin:21-jre

# Run as an unprivileged user, never root.
RUN groupadd --system app && useradd --system --gid app --create-home app
WORKDIR /app

# Least-changing layers first.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER app

# NFR-07: size the heap from the container's memory limit (75% of it), so the same image
# fits a 512 MB free-tier instance. Exit on OOM so the host restarts a clean process
# instead of leaving a half-dead JVM serving errors.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# The prod profile listens on $PORT (Render sets it) and falls back to 8777.
EXPOSE 8777

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
