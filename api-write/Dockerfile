# Stage 1: build
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /workspace
COPY ../pom.xml ./pom.xml
COPY ../common ./common
COPY api-read/pom.xml ./api-read/pom.xml
COPY api-write/pom.xml ./api-write/pom.xml
COPY api-rw/pom.xml ./api-rw/pom.xml
RUN mvn -pl common,api-write -am dependency:go-offline -q
COPY api-write/src ./api-write/src
RUN mvn -pl common,api-write -am package -DskipTests -q

# Stage 2: run
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=builder /workspace/api-write/target/*.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=70.0", \
  "-jar", "app.jar"]
