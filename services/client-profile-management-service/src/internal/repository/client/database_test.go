package client

import (
	"context"
	"database/sql"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"
	"uuid"

	"client-profile-management-service/src/internal/documents/documentstest"
	"client-profile-management-service/src/internal/encryption"
	"client-profile-management-service/src/internal/encryption/encryptiontest"
	"client-profile-management-service/src/internal/models"

	_ "github.com/lib/pq"
	"github.com/stretchr/testify/require"
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

// newTestRepository returns a repository whose data keys and blind indexes
// come from an in-memory protector, and whose documents are kept in memory
// (see documentStore).
func newTestRepository(t *testing.T) (*sql.DB, clientRepository, *encryptiontest.Protector) {
	t.Helper()
	db := openTestDB(t)
	protector := encryptiontest.New()
	return db, clientRepository{db: db, protector: protector, documents: documentstest.New()}, protector
}

// documentStore is the in-memory store newTestRepository gave repo.
func documentStore(repo clientRepository) *documentstest.Store {
	return repo.documents.(*documentstest.Store)
}

// clearContact removes any row with this ID, email or phone, which an
// earlier test may have left behind.
func clearContact(t *testing.T, db *sql.DB, id, email, phone string) {
	t.Helper()
	_, err := db.Exec(`DELETE FROM clients WHERE client_id = $1 OR email_hash = $2 OR phone_number_hash = $3`, id,
		encryptiontest.Index(encryption.NormalizeEmail(email)), encryptiontest.Index(encryption.NormalizePhone(phone)))
	require.NoError(t, err)
}

// seedClient stores a client through the repository, so its fields are
// encrypted the way the service stores them.
func seedClient(t *testing.T, db *sql.DB, repo clientRepository, id, email, phone string) models.Client {
	t.Helper()
	clearContact(t, db, id, email, phone)
	var err error
	profile := validRepositoryClient()
	profile.ClientID, err = uuid.Parse(id)
	require.NoError(t, err)
	profile.Email, profile.PhoneNumber = email, phone
	require.NoError(t, repo.CreateClient(context.Background(), profile))
	return profile
}

// markDeleted soft-deletes a seeded client (ADR-0036).
func markDeleted(t *testing.T, db *sql.DB, id string) {
	t.Helper()
	_, err := db.Exec(`UPDATE clients SET deleted_at = now() WHERE client_id = $1`, id)
	require.NoError(t, err)
}

func validRepositoryClient() models.Client {
	return models.Client{
		ClientID: uuid.New(), FirstName: "Alice", LastName: "Smith",
		DateOfBirth: time.Date(1990, 5, 10, 0, 0, 0, 0, time.UTC), Gender: "female",
		Email: "alice-new@example.test", PhoneNumber: "+6560000199", Address: "1 Example Street",
		City: "Singapore", State: "Singapore", Country: "Singapore", PostalCode: "123456",
		VerificationStatus: "UNVERIFIED", AssignedAgentID: "00000000-0000-0000-0000-000000000901",
	}
}
