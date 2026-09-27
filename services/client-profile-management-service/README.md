# Client Profile Management Service (Feature 2)

## Start locally

The local service still verifies Cognito access tokens. Provide the shared user-pool issuer URL and app-client ID when starting it:

```sh
make run COGNITO_ISSUER_URI=https://cognito-idp.<region>.amazonaws.com/<user-pool-id> COGNITO_APP_CLIENT_ID=<app-client-id>
```

The command starts PostgreSQL, initializes or migrates its schema, and runs the API at `http://localhost:8080`. `GET /health` is public; all `/api` routes require a Cognito **access token**. Set `Authorization: Bearer <access-token>` in Bruno or your API client.

To stop PostgreSQL and other Compose dependencies while keeping the data volume, run `make down`. To delete the local database data, run `make db-reset`.

## Cognito authorization

Feature 1 provisions users in the shared Cognito user pool and assigns the `ADMIN` or `AGENT` group. This service validates the token's RS256 signature against the pool's JWKS, plus its issuer, app-client ID, expiration, and `token_use=access` claim. It rejects ID tokens and tokens without a supported group.

The verified Cognito `sub` is the creator identity. Agents may read, update, verify, and delete only their own client profiles and create/delete accounts belonging to those profiles. Admins can access every client. The service ignores caller-supplied agent IDs and replaces the header from the authenticated subject before invoking the existing controller.

Configure API Gateway with a JWT authorizer for the same Cognito issuer and app-client ID as an additional edge check. This service also validates every token itself, so direct service access does not bypass authentication.

## Production transport security

Set `APP_ENV=production`, `APP_TLS_CERT_FILE`, and `APP_TLS_KEY_FILE` to serve HTTPS. Set `DB_SSLMODE=verify-full` and `DB_SSLROOTCERT` to verify PostgreSQL TLS. The service defaults to production security and refuses to start in production mode if these settings are missing; `make run` explicitly sets `APP_ENV=local`. If Cognito access tokens use resource-bound `aud` claims, also set `COGNITO_API_AUDIENCE` to the expected value. Local HTTP and `sslmode=disable` are intended only for local development.

RDS encryption-at-rest settings and Feature 2 hash-chain audit-write integration still require infrastructure and Auditor changes; those components are not present in this checkout.
