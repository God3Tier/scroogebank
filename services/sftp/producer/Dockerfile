FROM golang:1.27.1-bookworm AS build

WORKDIR /work

COPY go.mod .
COPY src ./src

RUN CGO_ENABLED=0 go build -o /producer ./src/main.go

FROM scratch

COPY --from=build /producer /producer

ENTRYPOINT ["/producer"]
