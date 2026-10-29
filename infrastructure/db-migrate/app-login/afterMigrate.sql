-- Gives the service its own login (ADR-0028 decision 3). It reads and changes
-- rows but cannot change the schema: the master user that runs the migrations
-- owns every table, and PostgreSQL 17 lets no other role create objects in
-- public. The image copies this file into the clients, accounts, users,
-- alerts and engagement directories, and Flyway runs it after every migrate, so a table that
-- a later migration adds is granted with no change here. The login and its
-- password are Flyway placeholders that the service's db-migrate container
-- fills from Secrets Manager, so the password never appears here, and setting
-- it every time keeps it equal to the secret.

-- Tasks that start together run this at once, and concurrent GRANTs on one
-- table can fail. Flyway runs this file in one transaction, which holds the
-- lock until it ends.
SELECT pg_advisory_xact_lock(hashtext('db-migrate logins'));

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${app_login}') THEN
        CREATE ROLE ${app_login} LOGIN;
    END IF;
END
$$;

ALTER ROLE ${app_login} PASSWORD '${app_password}';

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ${app_login};
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ${app_login};
-- Only db-migrate records migrations.
REVOKE ALL ON flyway_schema_history FROM ${app_login};
