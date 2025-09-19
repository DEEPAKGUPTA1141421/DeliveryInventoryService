# Use OpenJDK as base image
FROM eclipse-temurin:21-jdk AS build

# Set working directory
WORKDIR /app

# Copy Gradle/Maven files first for caching dependencies
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Download dependencies (skip tests to speed up build)
RUN ./mvnw dependency:go-offline -B

# Copy source code
COPY src src

# Package the application
RUN ./mvnw package -DskipTests

# --- Runtime Stage ---
FROM eclipse-temurin:21-jdk

# Set working directory
WORKDIR /app

# Copy built JAR from build stage
COPY --from=build /app/target/*.jar app.jar

# Expose port (Cloud Run expects 8080)
EXPOSE 8080

# Run the application
ENTRYPOINT ["java","-jar","app.jar"]
