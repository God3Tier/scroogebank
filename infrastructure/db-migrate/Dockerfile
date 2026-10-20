# db-migrate: Flyway with the Go services' schemas, one directory per
# database (ADR-0028 decision 3). In AWS it runs as an init container in each
# service's task, as that database's master user, and the service starts only
# after it succeeds (stacks/crm/services.tf). Build from the repository root:
#   docker build -t crm/db-migrate:$TAG -f infrastructure/db-migrate/Dockerfile .
#
# Each file in a service's data/ directory is a versioned migration named
# V<n>_<description>.sql. Flyway records a checksum for every file it applies,
# so a file that has been deployed is never edited: put the change in the
# next version (docs/database/migrations.md).
FROM flyway/flyway:11.7.2-alpine

# One underscore after the version, as the services name their files. The
# databases may still be starting when a stack starts.
ENV FLYWAY_SQL_MIGRATION_SEPARATOR=_ \
    FLYWAY_CONNECT_RETRIES=10

COPY services/client-profile-management-service/data/ /flyway/sql/clients/
COPY services/account-service/data/ /flyway/sql/accounts/
COPY services/sftp/receiver/data/ /flyway/sql/transactions/
COPY services/auditor/data/ /flyway/sql/audit/
COPY infrastructure/db-migrate/audit/ /flyway/sql/audit/

# The receiver and the auditor name their only file without a version, and
# their Makefiles and Compose files use those names.
RUN mv /flyway/sql/transactions/init.sql /flyway/sql/transactions/V1_init.sql \
    && mv /flyway/sql/audit/schema.sql /flyway/sql/audit/V1_schema.sql

CMD ["migrate"]
