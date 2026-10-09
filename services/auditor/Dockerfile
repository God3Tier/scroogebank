# Build the image from the repository root, because the gRPC code is generated
# from the shared proto/ directory (ADR-0021):
#   docker build -f services/auditor/Dockerfile .
# The tools target needs no sources, so it can be built from either directory.

FROM golang:1.27.1-bookworm AS protoc

ARG PROTOC_GEN_GO_VERSION=v1.36.11
ARG PROTOC_GEN_GO_GRPC_VERSION=v1.5.1

# libprotobuf-dev provides the well-known types, such as timestamp.proto,
# under /usr/include.
RUN apt-get update \
	&& apt-get install -y --no-install-recommends protobuf-compiler libprotobuf-dev \
	&& rm -rf /var/lib/apt/lists/* \
	&& go install google.golang.org/protobuf/cmd/protoc-gen-go@${PROTOC_GEN_GO_VERSION} \
	&& go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@${PROTOC_GEN_GO_GRPC_VERSION}

FROM protoc AS build

WORKDIR /app
COPY services/auditor/go.mod services/auditor/go.sum ./
RUN go mod download
COPY services/auditor/src ./src
COPY proto /proto
RUN protoc -I /proto -I /usr/include \
	--go_out=src --go_opt=module=auditor/src \
	--go-grpc_out=src --go-grpc_opt=module=auditor/src \
	/proto/audit/v1/audit.proto
RUN CGO_ENABLED=0 GOOS=linux go build -o /auditor ./src/serverd

FROM protoc AS tools

ARG SQLBOILER_VERSION=v4.19.7
ARG MOCKERY_VERSION=v3.8.0

RUN go install github.com/aarondl/sqlboiler/v4@${SQLBOILER_VERSION} \
	&& go install github.com/aarondl/sqlboiler/v4/drivers/sqlboiler-psql@${SQLBOILER_VERSION} \
	&& go install github.com/vektra/mockery/v3@${MOCKERY_VERSION}

FROM gcr.io/distroless/static-debian12:nonroot

COPY --from=build /auditor /auditor

EXPOSE 8080
ENTRYPOINT ["/auditor"]
