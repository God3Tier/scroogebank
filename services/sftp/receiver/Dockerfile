# The default target is transaction-service, the receiver's read API
# (src/serverd), as ECS runs it. It compiles the sqlboiler ORM code in
# src/internal/repository/orm, which is git-ignored: run make sqlboiler in
# services/sftp first. The tools target is the development image that the
# compose file's go-tools and receiver-api use.
FROM golang:1.27.1-bookworm AS build

WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY src ./src

RUN CGO_ENABLED=0 GOOS=linux go build \
	-ldflags="-s -w" \
	-o /transaction-service \
	./src/serverd

FROM golang:1.27.1-bookworm AS tools

ARG SQLBOILER_VERSION=v4.19.7
# mockery v3, as in the other Go services: v2 cannot load packages built with
# Go 1.27.
ARG MOCKERY_VERSION=v3.8.0

# zip packages the transaction-ingest Lambda function (make lambda).
RUN apt-get update \
	&& apt-get install -y --no-install-recommends zip \
	&& rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
COPY go.mod go.sum ./
RUN go mod download

RUN go install github.com/aarondl/sqlboiler/v4@${SQLBOILER_VERSION} \
	&& go install github.com/aarondl/sqlboiler/v4/drivers/sqlboiler-psql@${SQLBOILER_VERSION} \
	&& go install github.com/vektra/mockery/v3@${MOCKERY_VERSION}

ENV PATH="/go/bin:${PATH}"

CMD ["sh"]

FROM gcr.io/distroless/static-debian12:nonroot

COPY --from=build /transaction-service /transaction-service

EXPOSE 8080
ENTRYPOINT ["/transaction-service"]
