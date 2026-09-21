FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
# Build from the service's own POM, not the root reactor: the parent resolves
# via <relativePath>, so the image never needs other services' POMs.
COPY pom.xml /build/pom.xml
COPY services/user-service/pom.xml /build/services/user-service/pom.xml
RUN mvn -f /build/services/user-service/pom.xml dependency:go-offline -B || true
COPY services/user-service /build/services/user-service
RUN mvn -f /build/services/user-service/pom.xml package -DskipTests -B

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=build /build/services/user-service/target/user-service.jar /app/app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
