# -------- Stage 1: Build --------
FROM eclipse-temurin:17-jdk AS build

WORKDIR /app

# Copy Maven wrapper + settings
COPY mvnw pom.xml ./
COPY .mvn .mvn

# Fix mvnw permission issue
RUN chmod +x mvnw

# Download dependencies (offline build support)
RUN ./mvnw dependency:go-offline -B

# Copy the entire project
COPY src src

# Build the application
RUN ./mvnw package -DskipTests

# -------- Stage 2: Run --------
FROM eclipse-temurin:17-jre

WORKDIR /app

# Copy built JAR from build stage
COPY --from=build /app/target/*.jar app.jar

# Spring Boot will use $PORT (set by Cloud Run)
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
