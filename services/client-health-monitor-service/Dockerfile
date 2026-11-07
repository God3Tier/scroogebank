FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml /build/pom.xml
COPY services/client-health-monitor-service/pom.xml /build/services/client-health-monitor-service/pom.xml
RUN mvn -f /build/services/client-health-monitor-service/pom.xml dependency:go-offline -B
COPY services/client-health-monitor-service /build/services/client-health-monitor-service
RUN mvn -f /build/services/client-health-monitor-service/pom.xml package -DskipTests -B

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S clienthealth && adduser -S -G clienthealth clienthealth
WORKDIR /app
COPY --from=build /build/services/client-health-monitor-service/target/client-health-monitor-service.jar /app/app.jar
USER clienthealth
EXPOSE 8087
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
