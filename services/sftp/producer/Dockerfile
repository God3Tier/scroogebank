FROM golang:1.27.1-alpine AS build

WORKDIR /work

COPY go.mod .
COPY src ./src

RUN go build -o /producer ./src/main.go