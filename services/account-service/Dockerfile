FROM golang:1.27.1-bookworm AS build

WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY src ./src

RUN CGO_ENABLED=0 GOOS=linux go build \
	-ldflags="-s -w" \
	-o /account-service \
	./src/serverd

FROM golang:1.27.1-bookworm AS tools

ARG SQLBOILER_VERSION=v4.19.7
ARG MOCKERY_VERSION=v3.8.0

RUN go install github.com/aarondl/sqlboiler/v4@${SQLBOILER_VERSION} \
	&& go install github.com/aarondl/sqlboiler/v4/drivers/sqlboiler-psql@${SQLBOILER_VERSION} \
	&& go install github.com/vektra/mockery/v3@${MOCKERY_VERSION}

FROM gcr.io/distroless/static-debian12:nonroot

COPY --from=build /account-service /account-service

EXPOSE 8083
ENTRYPOINT ["/account-service"]
