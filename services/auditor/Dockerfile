FROM golang:1.27.1 AS build

WORKDIR /app
COPY go.mod ./
COPY go.sum ./
COPY src ./src

RUN CGO_ENABLED=0 GOOS=linux go build -o /auditor ./src/serverd

FROM golang:1.27.1 AS tools

ARG SQLBOILER_VERSION=v4.19.7
ARG MOCKERY_VERSION=v3.8.0

RUN go install github.com/aarondl/sqlboiler/v4@${SQLBOILER_VERSION} \
	&& go install github.com/aarondl/sqlboiler/v4/drivers/sqlboiler-psql@${SQLBOILER_VERSION} \
	&& go install github.com/vektra/mockery/v3@${MOCKERY_VERSION}

FROM gcr.io/distroless/static-debian12:nonroot

COPY --from=build /auditor /auditor

EXPOSE 8080
ENTRYPOINT ["/auditor"]
