package routers

import (
	"database/sql"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"client-profile-management-service/src/internal/models"
	"client-profile-management-service/src/internal/security"

	"github.com/gin-gonic/gin"
	_ "github.com/lib/pq"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const (
	documentsClientID = "00000000-0000-0000-0000-000000000161"
	documentsOwner    = "agent-owner-161"
	documentID        = "0199f1d2-0000-7000-8000-000000000161"
)

// recordingClients stands in for the client controller and records which
// handler each request reached, with the query the route adapter built.
type recordingClients struct {
	reached []string
	callers []models.Caller
}

func (r *recordingClients) record(name string, w http.ResponseWriter, req *http.Request) {
	r.reached = append(r.reached, name+"?"+req.URL.RawQuery)
	w.WriteHeader(http.StatusOK)
}

func (r *recordingClients) CreateClientProfile(w http.ResponseWriter, req *http.Request) {
	r.record("CreateClientProfile", w, req)
}
func (r *recordingClients) VerifyClientIdentity(w http.ResponseWriter, req *http.Request) {
	r.record("VerifyClientIdentity", w, req)
}
func (r *recordingClients) UpdateClientInformation(w http.ResponseWriter, req *http.Request) {
	r.record("UpdateClientInformation", w, req)
}
func (r *recordingClients) GetClientProfile(w http.ResponseWriter, req *http.Request) {
	r.record("GetClientProfile", w, req)
}
func (r *recordingClients) ListClients(w http.ResponseWriter, req *http.Request, caller models.Caller) {
	r.callers = append(r.callers, caller)
	r.record("ListClients", w, req)
}
func (r *recordingClients) DeleteClientProfile(w http.ResponseWriter, req *http.Request) {
	r.record("DeleteClientProfile", w, req)
}
func (r *recordingClients) UploadClientDocument(w http.ResponseWriter, req *http.Request) {
	r.record("UploadClientDocument", w, req)
}
func (r *recordingClients) ListClientDocuments(w http.ResponseWriter, req *http.Request) {
	r.record("ListClientDocuments", w, req)
}
func (r *recordingClients) GetClientDocument(w http.ResponseWriter, req *http.Request) {
	r.record("GetClientDocument", w, req)
}
func (r *recordingClients) ReassignClients(w http.ResponseWriter, req *http.Request) {
	r.record("ReassignClients", w, req)
}

// openRouterTestDB loads one client owned by documentsOwner. The ownership
// check reads the clients table, so these tests need the test database.
func openRouterTestDB(t *testing.T) *sql.DB {
	t.Helper()
	dsn := "host=" + envOrDefault("DB_HOST", "localhost") +
		" port=" + envOrDefault("DB_PORT", "5433") +
		" user=" + envOrDefault("DB_USER", "postgres") +
		" password=" + envOrDefault("DB_PASSWORD", "postgres") +
		" dbname=" + envOrDefault("DB_NAME", "myapp") +
		" sslmode=disable"
	db, err := sql.Open("postgres", dsn)
	require.NoError(t, err)
	require.NoError(t, db.Ping())
	t.Cleanup(func() { _ = db.Close() })
	_, err = db.Exec(`
		DELETE FROM clients WHERE client_id = '` + documentsClientID + `';
		INSERT INTO clients (
			client_id, first_name, last_name, date_of_birth, gender, email, email_hash, phone_number,
			phone_number_hash, address, city, state, country, postal_code, verification_status,
			assigned_agent_id, created_at, updated_at
		) VALUES
			('` + documentsClientID + `', 'Owned', 'Client', '1990-05-10', 'female', 'owned-161@example.test', 'email-hash-161', '+6560000161', 'phone-hash-161',
			 '1 Example Street', 'Singapore', 'Singapore', 'Singapore', '123456', 'UNVERIFIED',
			 '` + documentsOwner + `', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);`)
	require.NoError(t, err)
	return db
}

func envOrDefault(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

// Agents use their own clients; admins only read every client (ADR-0059).
func TestClientRoutesAreForTheOwnerAndReadOnlyForAdmins(t *testing.T) {
	db := openRouterTestDB(t)
	gin.SetMode(gin.TestMode)
	requests := []struct {
		method, path, handler string
		write                 bool
	}{
		{http.MethodPost, "/api/clients", "CreateClientProfile?", true},
		{http.MethodGet, "/api/clients/" + documentsClientID, "GetClientProfile?id=" + documentsClientID, false},
		{http.MethodPut, "/api/clients/" + documentsClientID, "UpdateClientInformation?id=" + documentsClientID, true},
		{http.MethodDelete, "/api/clients/" + documentsClientID, "DeleteClientProfile?id=" + documentsClientID, true},
		{http.MethodPost, "/api/clients/" + documentsClientID + "/documents", "UploadClientDocument?id=" + documentsClientID, true},
		{http.MethodGet, "/api/clients/" + documentsClientID + "/documents", "ListClientDocuments?id=" + documentsClientID, false},
		{http.MethodGet, "/api/clients/" + documentsClientID + "/documents/" + documentID, "GetClientDocument?documentId=" + documentID + "&id=" + documentsClientID, false},
		{http.MethodPost, "/api/clients/" + documentsClientID + "/verify", "VerifyClientIdentity?id=" + documentsClientID, true},
	}
	principals := map[string]security.Principal{
		"the owning agent": {Subject: documentsOwner, Role: "AGENT"},
		"an admin":         {Subject: "admin-161", Role: "ADMIN"},
		"another agent":    {Subject: "agent-other-161", Role: "AGENT"},
	}
	for name, principal := range principals {
		for _, request := range requests {
			t.Run(name+" "+request.method+" "+strings.TrimPrefix(request.path, "/api/clients"), func(t *testing.T) {
				router := gin.New()
				clients := &recordingClients{}
				noAudit := func(c *gin.Context) { c.Next() }
				principal := principal
				RegisterRoutes(router, clients, &recordingAggregates{}, db, signedInAs(&principal), noAudit)
				res := httptest.NewRecorder()

				router.ServeHTTP(res, httptest.NewRequest(request.method, request.path, nil))

				switch {
				case principal.Role == "ADMIN" && request.write:
					assert.Equal(t, http.StatusForbidden, res.Code, "admins only read client data")
					assert.Empty(t, clients.reached)
				case principal.Subject == "agent-other-161" && request.path != "/api/clients":
					assert.Equal(t, http.StatusNotFound, res.Code, "another agent's client looks missing")
					assert.Empty(t, clients.reached)
				default:
					assert.Equal(t, http.StatusOK, res.Code)
					assert.Equal(t, []string{request.handler}, clients.reached)
				}
			})
		}
	}
}

// Reassigning is the one admin write on client data (ADR-0060).
func TestReassignmentIsForAdminsOnly(t *testing.T) {
	gin.SetMode(gin.TestMode)
	principals := map[string]struct {
		principal  security.Principal
		wantStatus int
	}{
		"an admin": {security.Principal{Subject: "admin-161", Role: "ADMIN"}, http.StatusOK},
		"an agent": {security.Principal{Subject: documentsOwner, Role: "AGENT"}, http.StatusForbidden},
	}
	for name, test := range principals {
		t.Run(name, func(t *testing.T) {
			router := gin.New()
			clients := &recordingClients{}
			noAudit := func(c *gin.Context) { c.Next() }
			principal := test.principal
			RegisterRoutes(router, clients, &recordingAggregates{}, nil, signedInAs(&principal), noAudit)
			res := httptest.NewRecorder()

			router.ServeHTTP(res, httptest.NewRequest(http.MethodPost, "/api/clients/reassignments", strings.NewReader(`{}`)))

			assert.Equal(t, test.wantStatus, res.Code)
			if test.wantStatus == http.StatusOK {
				assert.Equal(t, []string{"ReassignClients?"}, clients.reached)
			} else {
				assert.Empty(t, clients.reached)
			}
		})
	}
}
