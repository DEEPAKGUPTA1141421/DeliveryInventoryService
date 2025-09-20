# Use lightweight JDK
FROM eclipse-temurin:17-jdk-alpine as build

# Set working dir
WORKDIR /app

# Copy Gradle/Maven files first for caching
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the JAR
RUN ./mvnw package -DskipTests

# ==========================
# Runtime image
# ==========================
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Copy JAR from build stage
COPY --from=build /app/target/DeliveryInventoryService-0.0.1-SNAPSHOT.jar app.jar

# Expose the Cloud Run port
EXPOSE 8080

# Run app
ENTRYPOINT ["java","-jar","app.jar"]
