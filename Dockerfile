# Use Eclipse Temurin OpenJDK 21
FROM eclipse-temurin:21-jdk AS build

# Set working directory
WORKDIR /app

# Copy Maven wrapper and project files
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Make mvnw executable
RUN chmod +x mvnw

# Download dependencies
RUN ./mvnw dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application
RUN ./mvnw package -DskipTests

# Use a smaller runtime image
FROM eclipse-temurin:21-jre

# Set working directory
WORKDIR /app

# Copy the jar from the build stage
COPY --from=build /app/target/DeliveryInventoryService-0.0.1-SNAPSHOT.jar app.jar

# Expose the port Cloud Run will use
ENV PORT 8080
EXPOSE 8080

# Start the application
ENTRYPOINT ["java","-jar","app.jar"]
