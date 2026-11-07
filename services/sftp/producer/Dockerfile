FROM golang:1.27.1-bookworm AS build

WORKDIR /work

COPY go.mod .
COPY src ./src

RUN CGO_ENABLED=0 go build -o /producer ./src/main.go

# `make test` builds this stage to run the producer's tests. The default
# build skips it.
FROM build AS test
RUN go test ./...

FROM scratch

COPY --from=build /producer /producer

ENTRYPOINT ["/producer"]
