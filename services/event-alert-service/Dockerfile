FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
# Build from the service's own POM, not the root reactor: the parent resolves
# via <relativePath>, so the image never needs other services' POMs.
COPY pom.xml /build/pom.xml
COPY services/event-alert-service/pom.xml /build/services/event-alert-service/pom.xml
RUN mvn -f /build/services/event-alert-service/pom.xml dependency:go-offline -B || true
COPY services/event-alert-service /build/services/event-alert-service
RUN mvn -f /build/services/event-alert-service/pom.xml package -DskipTests -B

# One image, two modes (ADR-0031 decision 1): ALERT_MODE=API serves
# /api/alerts on 8080; ALERT_MODE=WORKER runs one ingestion window and exits.
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S alerts && adduser -S -G alerts alerts
WORKDIR /app
COPY --from=build /build/services/event-alert-service/target/event-alert-service.jar /app/app.jar
USER alerts
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
