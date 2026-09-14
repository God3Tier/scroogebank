# Auditor service

Minimal Go service scaffold with a health endpoint.

## Run locally

From `services/auditor`:

```sh
go run ./src
```

The server listens on `http://localhost:8080` by default. Set `PORT` to use a different port.

```sh
PORT=9090 go run ./src
```

Check service health:

```sh
curl http://localhost:8080/health
```

Expected response:

```json
{"status":"ok"}
```

## Test

```sh
go test ./...
```

## Build with Docker

```sh
docker build -t auditor .
docker run --rm -p 8080:8080 auditor
```