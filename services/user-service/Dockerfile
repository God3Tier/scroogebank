FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
# Build from the service's own POM, not the root reactor: the parent resolves
# via <relativePath>, so the image never needs other services' POMs.
COPY pom.xml /build/pom.xml
COPY services/user-service/pom.xml /build/services/user-service/pom.xml
RUN mvn -f /build/services/user-service/pom.xml dependency:go-offline -B || true
COPY services/user-service /build/services/user-service
# The shared gRPC contract is the source for this service's auditor client stubs.
COPY proto /build/proto
RUN mvn -f /build/services/user-service/pom.xml package -DskipTests -B

# glibc, not Alpine (musl): grpc-netty-shaded bundles netty-tcnative built for
# glibc, and loading it on musl crashes the JVM (SIGSEGV) once the auditor
# channel uses TLS, as it does in AWS.
FROM eclipse-temurin:25-jre
# The tls-init container runs in this image and downloads the RDS CA bundle
# with wget (infrastructure/terraform/modules/ecs-service).
RUN apt-get update \
	&& apt-get install -y --no-install-recommends wget \
	&& rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/services/user-service/target/user-service.jar /app/app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
