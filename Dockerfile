# Stage 1: Build the React Frontend
FROM node:20-alpine AS frontend-builder
WORKDIR /app/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# Stage 2: Build the Spring Boot Backend
FROM maven:3.9.6-eclipse-temurin-21-alpine AS backend-builder
WORKDIR /app/backend
COPY backend/pom.xml ./
# Resolve dependencies offline to speed up subsequent builds
RUN mvn dependency:go-offline
COPY backend/src ./src
# Copy compiled frontend assets from Stage 1 into Spring Boot static resources folder
COPY --from=frontend-builder /app/frontend/dist ./src/main/resources/static
RUN mvn clean package -DskipTests

# Stage 3: Package the Runnable Container
FROM eclipse-temurin:21-jdk-alpine
WORKDIR /app
COPY --from=backend-builder /app/backend/target/backend-0.0.1-SNAPSHOT.jar app.jar

# Expose server port
EXPOSE 8080

# Run the app
ENTRYPOINT ["java", "-jar", "app.jar"]
