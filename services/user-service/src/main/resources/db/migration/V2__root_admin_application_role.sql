-- The root account was previously an ADMIN row protected by deletable=false.
-- Keep its Cognito ADMIN group while distinguishing it in the application.
ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
    CHECK (role IN ('ROOT_ADMIN', 'ADMIN', 'AGENT'));

UPDATE users
SET role = 'ROOT_ADMIN'
WHERE deletable = FALSE AND role = 'ADMIN';

CREATE UNIQUE INDEX uk_users_single_root_admin
    ON users (role)
    WHERE role = 'ROOT_ADMIN';
