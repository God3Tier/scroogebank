package client

import (
	"database/sql"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"
	"uuid"

	"client-profile-management-service/src/internal/models"

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
		_ = db.Close()
		t.Fatalf("connect to test database at %s:%s: %v", host, port, err)
	}
	t.Cleanup(func() { _ = db.Close() })
	return db
}

func loadTestFixture(t *testing.T, db *sql.DB, name string) {
	t.Helper()
	_, currentFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("locate repository test fixtures")
	}
	fixturePath := filepath.Join(filepath.Dir(currentFile), "testcase", name)
	sqlBytes, err := os.ReadFile(fixturePath)
	if err != nil {
		t.Fatalf("read fixture %s: %v", name, err)
	}
	if _, err := db.Exec(string(sqlBytes)); err != nil {
		t.Fatalf("execute fixture %s: %v", name, err)
	}
}

func envOrDefault(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

func validRepositoryClient() models.Client {
	return models.Client{
		ClientID: uuid.New(), FirstName: "Alice", LastName: "Smith",
		DateOfBirth: time.Date(1990, 5, 10, 0, 0, 0, 0, time.UTC), Gender: "female",
		Email: "alice-new@example.test", PhoneNumber: "+6560000199", Address: "1 Example Street",
		City: "Singapore", State: "Singapore", Country: "Singapore", PostalCode: "123456",
		VerificationStatus: "UNVERIFIED", CreatedByAgentID: "00000000-0000-0000-0000-000000000901",
	}
}
