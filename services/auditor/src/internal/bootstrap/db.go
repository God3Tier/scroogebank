// Package bootstrap holds startup logic shared by the auditor's entrypoints
// (the server in src/serverd and chain-verify in src/chainverify). It exists
// because a Go main package cannot be imported by another package, so
// anything more than one entrypoint needs lives here instead.
package bootstrap

import (
	"database/sql"
	"fmt"
	"os"
	"time"
)

// InitialiseDB opens the auditor's Postgres connection using the standard
// DB_NAME/HOST/USER/PASSWORD/SSLMODE environment variables.
func InitialiseDB() (*sql.DB, error) {
	dbname := os.Getenv("DB_NAME")
	host := os.Getenv("HOST")
	user := os.Getenv("USER")
	password := os.Getenv("PASSWORD")
	sslmode := os.Getenv("SSLMODE")

	db, err := sql.Open("postgres", fmt.Sprintf("dbname=%s host=%s user=%s password=%s sslmode=%s", dbname, host, user, password, sslmode))
	if err != nil {
		return nil, err
	}
	// Bound the pool. Without a limit, a burst opens one connection per request
	// and exhausts the db.t4g.micro's connection slots ("too many clients"):
	// requests fail and audit events are lost (load test, 2026-11-02). Ten per
	// task keeps six tasks at 60.
	db.SetMaxOpenConns(10)
	db.SetMaxIdleConns(10)
	db.SetConnMaxIdleTime(5 * time.Minute)
	return db, nil
}
