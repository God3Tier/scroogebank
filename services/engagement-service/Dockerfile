FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
# Build from the service's own POM, not the root reactor: the parent resolves
# via <relativePath>, so the image never needs other services' POMs.
COPY pom.xml /build/pom.xml
COPY services/engagement-service/pom.xml /build/services/engagement-service/pom.xml
RUN mvn -f /build/services/engagement-service/pom.xml dependency:go-offline -B || true
COPY services/engagement-service /build/services/engagement-service
RUN mvn -f /build/services/engagement-service/pom.xml package -DskipTests -B

# Serves /api/engagement on 8086 and runs the mail dispatcher (ADR-0051).
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S engagement && adduser -S -G engagement engagement
WORKDIR /app
COPY --from=build /build/services/engagement-service/target/engagement-service.jar /app/app.jar
USER engagement
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
