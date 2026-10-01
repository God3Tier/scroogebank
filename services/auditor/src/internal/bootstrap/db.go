// Package bootstrap holds startup logic shared by the auditor's entrypoints
// (the server in src/serverd and the cronjob in src/cronjob). It exists
// because a Go main package cannot be imported by another package, so
// anything more than one entrypoint needs lives here instead.
package bootstrap

import (
	"database/sql"
	"fmt"
	"os"
)

// InitialiseDB opens the auditor's Postgres connection using the standard
// DB_NAME/HOST/USER/PASSWORD/SSLMODE environment variables.
func InitialiseDB() (*sql.DB, error) {
	dbname := os.Getenv("DB_NAME")
	host := os.Getenv("HOST")
	user := os.Getenv("USER")
	password := os.Getenv("PASSWORD")
	sslmode := os.Getenv("SSLMODE")

	return sql.Open("postgres", fmt.Sprintf("dbname=%s host=%s user=%s password=%s sslmode=%s", dbname, host, user, password, sslmode))
}
