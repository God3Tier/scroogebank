# db-migrate: Flyway with every service's schema, one directory per database
# (ADR-0028 decision 3). In AWS it runs as an init container in each task of a
# service that has a database, as that database's master user. It also gives
# the service its own login without schema rights, and the service starts only
# after it succeeds (stacks/crm/services.tf). Build from the repository root:
#   docker build -t crm/db-migrate:$TAG -f infrastructure/db-migrate/Dockerfile .
#
# Each file in a Go service's data/ directory is a versioned migration named
# V<n>_<description>.sql. user-service and event-alert-service keep theirs in
# src/main/resources/db/migration, named V<n>__<description>.sql, where
# Spring Boot's Flyway applies them locally; Terraform sets the separator __
# for those two directories. Flyway records a checksum for every file it
# applies, so a file that has been deployed is never edited: put the change
# in the next version (docs/database/migrations.md).
FROM flyway/flyway:11.7.2-alpine

# One underscore after the version, as the Go services name their files. The
# databases may still be starting when a stack starts.
ENV FLYWAY_SQL_MIGRATION_SEPARATOR=_ \
    FLYWAY_CONNECT_RETRIES=10

COPY services/client-profile-management-service/data/ /flyway/sql/clients/
COPY services/account-service/data/ /flyway/sql/accounts/
COPY services/sftp/receiver/data/ /flyway/sql/transactions/
COPY services/auditor/data/ /flyway/sql/audit/
COPY services/user-service/src/main/resources/db/migration/ /flyway/sql/users/
COPY services/event-alert-service/src/main/resources/db/migration/ /flyway/sql/alerts/

# The logins: one service's login for each of these databases, the
# transaction logins and the audit logins.
COPY infrastructure/db-migrate/app-login/afterMigrate.sql /flyway/sql/clients/
COPY infrastructure/db-migrate/app-login/afterMigrate.sql /flyway/sql/accounts/
COPY infrastructure/db-migrate/app-login/afterMigrate.sql /flyway/sql/users/
COPY infrastructure/db-migrate/app-login/afterMigrate.sql /flyway/sql/alerts/
COPY infrastructure/db-migrate/transactions/ /flyway/sql/transactions/
COPY infrastructure/db-migrate/audit/ /flyway/sql/audit/

# The receiver and the auditor name their only file without a version, and
# their Makefiles and Compose files use those names.
RUN mv /flyway/sql/transactions/init.sql /flyway/sql/transactions/V1_init.sql \
    && mv /flyway/sql/audit/schema.sql /flyway/sql/audit/V1_schema.sql

CMD ["migrate"]
