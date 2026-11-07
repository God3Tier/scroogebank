package audit

import (
	"database/sql"
	"os"
	"path/filepath"
	"runtime"
	"testing"

	_ "github.com/lib/pq"
)

func openTestDB(t *testing.T) *sql.DB {
	t.Helper()
	host := envOrDefault("DB_HOST", "localhost")
	port := envOrDefault("DB_PORT", "5433")
	user := envOrDefault("DB_USER", "postgres")
	password := envOrDefault("DB_PASSWORD", "postgres")
	database := envOrDefault("DB_NAME", "myapp")
	dsn := "host=" + host + " port=" + port + " user=" + user + " password=" + password + " dbname=" + database + " sslmode=disable"

	db, err := sql.Open("postgres", dsn)
	if err != nil {
		t.Fatalf("open test database: %v", err)
	}
	if err := db.Ping(); err != nil {
		db.Close()
		t.Fatalf("connect to test database at %s:%s: %v", host, port, err)
	}
	t.Cleanup(func() { db.Close() })
	return db
}

// openTestDBAs connects as login, a login role created for the test that
// holds only role, one of the roles data/schema.sql grants (ADR-0022
// decision 2). The test password is not a secret: the database is a
// disposable test container.
func openTestDBAs(t *testing.T, admin *sql.DB, login, role string) *sql.DB {
	t.Helper()
	_, err := admin.Exec(`DO $$ BEGIN
		IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '` + login + `') THEN
			CREATE ROLE ` + login + ` LOGIN PASSWORD 'test' IN ROLE ` + role + `;
		END IF;
	END $$`)
	if err != nil {
		t.Fatalf("create login %s: %v", login, err)
	}

	dsn := "host=" + envOrDefault("DB_HOST", "localhost") + " port=" + envOrDefault("DB_PORT", "5433") +
		" user=" + login + " password=test dbname=" + envOrDefault("DB_NAME", "myapp") + " sslmode=disable"
	db, err := sql.Open("postgres", dsn)
	if err != nil {
		t.Fatalf("open test database as %s: %v", login, err)
	}
	t.Cleanup(func() { db.Close() })
	return db
}

func loadTestFixture(t *testing.T, db *sql.DB, name string) {
	t.Helper()
	_, currentFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("locate repository test directory")
	}
	path := filepath.Join(filepath.Dir(currentFile), "testcase", name)
	sqlBytes, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read test fixture %s: %v", name, err)
	}
	if _, err := db.Exec(string(sqlBytes)); err != nil {
		t.Fatalf("execute test fixture %s: %v", name, err)
	}
}

func envOrDefault(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}
