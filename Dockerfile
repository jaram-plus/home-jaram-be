# syntax=docker/dockerfile:1.7

# ===== Build stage =====
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# Cache gradle distribution + dependency resolution first
COPY gradle ./gradle
COPY gradlew build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies --quiet || true

# Build the bootable jar (skip tests; they run in CI, not in image build)
COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test

# ===== Runtime stage =====
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
RUN useradd --system --uid 10001 jaram
COPY --from=build /workspace/build/libs/*.jar /app/app.jar
USER jaram

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
