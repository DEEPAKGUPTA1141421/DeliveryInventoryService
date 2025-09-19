# Use Eclipse Temurin JDK 21 base image
FROM eclipse-temurin:21-jdk

# Set working directory
WORKDIR /app

# Copy Maven wrapper and pom.xml first for caching dependencies
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Make mvnw executable
RUN chmod +x mvnw

# Download Maven dependencies offline (skip tests)
RUN ./mvnw dependency:go-offline -B

# Copy the source code
COPY src ./src

# Package the application
RUN ./mvnw package -DskipTests

# Expose the port that Cloud Run sets via PORT environment variable
EXPOSE 8080

# Run the Spring Boot jar (replace with your jar name if different)
ENTRYPOINT ["java", "-jar", "target/DeliveryInventoryService-0.0.1-SNAPSHOT.jar"]
