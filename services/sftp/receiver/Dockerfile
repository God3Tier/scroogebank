FROM golang:1.27.1-bookworm

ARG SQLBOILER_VERSION=v4.19.7
# mockery v3, as in the other Go services: v2 cannot load packages built with
# Go 1.27.
ARG MOCKERY_VERSION=v3.8.0

WORKDIR /workspace
COPY go.mod go.sum ./
RUN go mod download

RUN go install github.com/aarondl/sqlboiler/v4@${SQLBOILER_VERSION} \
	&& go install github.com/aarondl/sqlboiler/v4/drivers/sqlboiler-psql@${SQLBOILER_VERSION} \
	&& go install github.com/vektra/mockery/v3@${MOCKERY_VERSION}

ENV PATH="/go/bin:${PATH}"

CMD ["sh"]
