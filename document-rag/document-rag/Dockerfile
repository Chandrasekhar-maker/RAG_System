# syntax=docker/dockerfile:1

# =================================================================
# Stage 1: Build stage (compile & package using Maven Wrapper)
# =================================================================
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /build

# Copy Maven wrapper and pom.xml first to take advantage of layer caching
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Normalize line endings and grant execution permission to wrapper script
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw

# Resolve dependencies in a separate layer
RUN ./mvnw dependency:go-offline -B || true

# Copy project source code
COPY src/ ./src/

# Package the application (tests can be skipped during container packaging)
RUN ./mvnw clean package -DskipTests -B

# =================================================================
# Stage 2: Runtime stage (lightweight JRE, non-root user)
# =================================================================
FROM eclipse-temurin:21-jre-jammy AS runner

# Create dedicated non-root application user and group for security
RUN addgroup --system --gid 1001 appgroup && \
    adduser --system --uid 1001 --ingroup appgroup --no-create-home appuser

WORKDIR /app

# Copy the packaged Spring Boot executable JAR from builder stage
COPY --from=builder --chown=appuser:appgroup /build/target/document-rag-0.0.1-SNAPSHOT.jar app.jar

# Switch to non-root execution
USER appuser

# Expose Spring Boot standard HTTP port
EXPOSE 8080

# Recommended container memory limits and JVM tuning
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# Launch Spring Boot application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
