FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
# Build the application
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Copy the built jar from the build stage
COPY --from=build /app/target/*.jar app.jar
# Expose the Spring Boot default port
EXPOSE 8080
# Set necessary environment variables (can be overridden at runtime)
ENV GEMINI_API_KEY=""
ENV OLLAMA_BASE_URL="http://host.docker.internal:11434"
ENV OLLAMA_CHAT_MODEL="qwen2.5:3b-instruct"
ENV APP_RAG_REFINER_ENABLED="false"
# Run the application
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
