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
	ownedAccountID   = "00000000-0000-0000-0000-000000000251"
	deletedAccountID = "00000000-0000-0000-0000-000000000252"
	ownerSubject     = "agent-sub-owner"
)

// RequireAccountOwner reads owner_agent_id from this service's own table, so
// it needs the test database rather than a mock: the query is what is tested.
func openAuthorizationTestDB(t *testing.T) *sql.DB {
	t.Helper()
	dsn := "host=" + envOrDefault("DB_HOST", "localhost") +
		" port=" + envOrDefault("DB_PORT", "5435") +
		" user=" + envOrDefault("DB_USER", "postgres") +
		" password=" + envOrDefault("DB_PASSWORD", "postgres") +
		" dbname=" + envOrDefault("DB_NAME", "accounts") +
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
		DELETE FROM accounts WHERE id IN ('` + ownedAccountID + `', '` + deletedAccountID + `');
		INSERT INTO accounts (id, client_id, owner_agent_id, account_type, status, opening_date, initial_deposit, currency, branch_id, deleted_at)
		VALUES
			('` + ownedAccountID + `', '00000000-0000-0000-0000-000000000250', '` + ownerSubject + `', 'savings', 'active', CURRENT_DATE, 0, 'SGD', 'SG-001', NULL),
			('` + deletedAccountID + `', '00000000-0000-0000-0000-000000000250', '` + ownerSubject + `', 'checking', 'active', CURRENT_DATE, 0, 'SGD', 'SG-001', CURRENT_TIMESTAMP);`)
	if err != nil {
		t.Fatalf("load account fixture: %v", err)
	}
	return db
}

func envOrDefault(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

func TestRequireAccountOwner(t *testing.T) {
	db := openAuthorizationTestDB(t)
	gin.SetMode(gin.TestMode)

	tests := map[string]struct {
		principal  *Principal
		accountID  string
		wantStatus int
	}{
		"the owning agent reaches the handler":      {principal: &Principal{Subject: ownerSubject, Role: "AGENT"}, accountID: ownedAccountID, wantStatus: http.StatusOK},
		"an admin reaches the handler":              {principal: &Principal{Subject: "admin-sub", Role: "ADMIN"}, accountID: ownedAccountID, wantStatus: http.StatusOK},
		"another agent sees not found":              {principal: &Principal{Subject: "agent-sub-other", Role: "AGENT"}, accountID: ownedAccountID, wantStatus: http.StatusNotFound},
		"a missing account looks the same":          {principal: &Principal{Subject: ownerSubject, Role: "AGENT"}, accountID: "00000000-0000-0000-0000-000000000299", wantStatus: http.StatusNotFound},
		"so does a deleted account":                 {principal: &Principal{Subject: ownerSubject, Role: "AGENT"}, accountID: deletedAccountID, wantStatus: http.StatusNotFound},
		"a malformed ID is left to the controller":  {principal: &Principal{Subject: ownerSubject, Role: "AGENT"}, accountID: "not-a-uuid", wantStatus: http.StatusOK},
		"an unauthenticated request is turned away": {accountID: ownedAccountID, wantStatus: http.StatusUnauthorized},
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
			router.GET("/api/accounts/:accountId", RequireAccountOwner(db, "accountId"), func(c *gin.Context) {
				c.Status(http.StatusOK)
			})
			res := httptest.NewRecorder()

			router.ServeHTTP(res, httptest.NewRequest(http.MethodGet, "/api/accounts/"+test.accountID, nil))

			assert.Equal(t, test.wantStatus, res.Code)
		})
	}
}
