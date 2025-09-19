# Use Eclipse Temurin JDK 21 as base
FROM eclipse-temurin:21-jdk

# Set working directory
WORKDIR /app

# Copy Maven wrapper and project files
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Give execute permission to Maven wrapper
RUN chmod +x mvnw

# Download dependencies offline (skip tests to speed up build)
RUN ./mvnw dependency:go-offline -B

# Copy the entire source code
COPY src ./src

# Build the application
RUN ./mvnw package -DskipTests

# Expose the port your Spring Boot app uses
EXPOSE 8082

# Run the Spring Boot application
ENTRYPOINT ["java","-jar","target/DeliveryInventoryService-0.0.1-SNAPSHOT.jar"]
