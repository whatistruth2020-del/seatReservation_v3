# =========================
# Build stage
# =========================
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy Maven configuration first
COPY pom.xml .

# Download dependencies
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build Spring Boot JAR
RUN mvn clean package -DskipTests


# =========================
# Runtime stage
# =========================
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy generated JAR from build stage
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]