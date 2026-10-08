package security

import (
	"database/sql"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"

	"github.com/gin-gonic/gin"
	_ "github.com/lib/pq"
	"github.com/stretchr/testify/assert"
)

const (
	ownedClientID   = "00000000-0000-0000-0000-000000000151"
	deletedClientID = "00000000-0000-0000-0000-000000000152"
	ownerSubject    = "agent-sub-owner"
)

// RequireClientOwner reads created_by_agent_id from the clients table, so it
// needs the test database rather than a mock: the query is what is tested.
func openAuthorizationTestDB(t *testing.T) *sql.DB {
	t.Helper()
	dsn := "host=" + envOrDefault("DB_HOST", "localhost") +
		" port=" + envOrDefault("DB_PORT", "5433") +
		" user=" + envOrDefault("DB_USER", "postgres") +
		" password=" + envOrDefault("DB_PASSWORD", "postgres") +
		" dbname=" + envOrDefault("DB_NAME", "myapp") +
		" sslmode=disable"
	db, err := sql.Open("postgres", dsn)
	if err != nil {
		t.Fatalf("open test database: %v", err)
	}
	if err := db.Ping(); err != nil {
		_ = db.Close()
		t.Fatalf("connect to test database: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })
	_, err = db.Exec(`
		DELETE FROM clients WHERE client_id IN ('` + ownedClientID + `', '` + deletedClientID + `');
		INSERT INTO clients (
			client_id, first_name, last_name, date_of_birth, gender, email, phone_number,
			address, city, state, country, postal_code, verification_status,
			created_by_agent_id, created_at, updated_at, deleted_at
		) VALUES
			('` + ownedClientID + `', 'Owned', 'Client', '1990-05-10', 'female', 'owned-151@example.test', '+6560000151',
			 '1 Example Street', 'Singapore', 'Singapore', 'Singapore', '123456', 'UNVERIFIED',
			 '` + ownerSubject + `', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL),
			('` + deletedClientID + `', 'Deleted', 'Client', '1990-05-10', 'female', 'deleted-152@example.test', '+6560000152',
			 '2 Example Street', 'Singapore', 'Singapore', 'Singapore', '123456', 'UNVERIFIED',
			 '` + ownerSubject + `', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);`)
	if err != nil {
		t.Fatalf("load client fixture: %v", err)
	}
	return db
}

func envOrDefault(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

func TestRequireClientOwner(t *testing.T) {
	db := openAuthorizationTestDB(t)
	gin.SetMode(gin.TestMode)

	tests := map[string]struct {
		principal  *Principal
		clientID   string
		wantStatus int
	}{
		"the owning agent reaches the handler":          {principal: &Principal{Subject: ownerSubject, Role: "AGENT"}, clientID: ownedClientID, wantStatus: http.StatusOK},
		"an admin reaches the handler":                  {principal: &Principal{Subject: "admin-sub", Role: "ADMIN"}, clientID: ownedClientID, wantStatus: http.StatusOK},
		"another agent sees not found":                  {principal: &Principal{Subject: "agent-sub-other", Role: "AGENT"}, clientID: ownedClientID, wantStatus: http.StatusNotFound},
		"the owner sees not found for a deleted client": {principal: &Principal{Subject: ownerSubject, Role: "AGENT"}, clientID: deletedClientID, wantStatus: http.StatusNotFound},
		"a missing client looks the same":               {principal: &Principal{Subject: ownerSubject, Role: "AGENT"}, clientID: "00000000-0000-0000-0000-000000000199", wantStatus: http.StatusNotFound},
		"a malformed ID is left to the controller":      {principal: &Principal{Subject: ownerSubject, Role: "AGENT"}, clientID: "not-a-uuid", wantStatus: http.StatusOK},
		"an unauthenticated request is turned away":     {clientID: ownedClientID, wantStatus: http.StatusUnauthorized},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			router := gin.New()
			router.Use(func(c *gin.Context) {
				if test.principal != nil {
					c.Set("principal", *test.principal)
				}
				c.Next()
			})
			router.GET("/api/clients/:clientId", RequireClientOwner(db, "clientId"), func(c *gin.Context) {
				c.Status(http.StatusOK)
			})
			res := httptest.NewRecorder()

			router.ServeHTTP(res, httptest.NewRequest(http.MethodGet, "/api/clients/"+test.clientID, nil))

			assert.Equal(t, test.wantStatus, res.Code)
		})
	}
}
