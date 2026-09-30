package security

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"regexp"

	orm "client-profile-management-service/src/internal/repository/orm"
	"github.com/gin-gonic/gin"
)

var uuidPathPattern = regexp.MustCompile(`(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

// RequireCRMRole prevents valid Cognito users without a supported CRM group
// from entering Feature 2 routes.
func RequireCRMRole() gin.HandlerFunc {
	return func(c *gin.Context) {
		principal, ok := principalFromGin(c)
		if !ok || (principal.Role != "ADMIN" && principal.Role != "AGENT") {
			c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "ADMIN or AGENT role required"})
			return
		}
		c.Next()
	}
}

// RequireClientOwner limits agent access to their own client profiles. Admins
// can access every profile. Missing and out-of-scope profiles both return 404
// so callers cannot enumerate another agent's client IDs.
func RequireClientOwner(db *sql.DB, parameter string) gin.HandlerFunc {
	return func(c *gin.Context) {
		principal, ok := principalFromGin(c)
		if !ok {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "authentication required"})
			return
		}
		if principal.Role == "ADMIN" {
			c.Next()
			return
		}
		clientID := c.Param(parameter)
		if !uuidPathPattern.MatchString(clientID) {
			c.Next() // Let the controller return its normal malformed-ID response.
			return
		}
		if !ownsClient(c, db, "SELECT created_by_agent_id FROM clients WHERE client_id = $1", clientID, principal.Subject) {
			return
		}
		c.Next()
	}
}

// RequireClientBodyOwner applies the same rule to account creation, where the
// target client ID is in the JSON body rather than the URL.
func RequireClientBodyOwner(db *sql.DB) gin.HandlerFunc {
	return func(c *gin.Context) {
		principal, ok := principalFromGin(c)
		if !ok {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "authentication required"})
			return
		}
		if principal.Role == "ADMIN" {
			c.Next()
			return
		}

		body, err := io.ReadAll(io.LimitReader(c.Request.Body, (1<<20)+1))
		if err != nil {
			c.AbortWithStatusJSON(http.StatusBadRequest, gin.H{"error": "invalid request body"})
			return
		}
		if len(body) > 1<<20 {
			c.AbortWithStatusJSON(http.StatusRequestEntityTooLarge, gin.H{"error": "request body too large"})
			return
		}
		c.Request.Body = io.NopCloser(bytes.NewReader(body))
		var input struct {
			ClientID string `json:"client_id"`
		}
		if json.Unmarshal(body, &input) != nil || !uuidPathPattern.MatchString(input.ClientID) {
			c.Next() // The controller validates malformed or incomplete JSON.
			return
		}
		if !ownsClient(c, db, "SELECT created_by_agent_id FROM clients WHERE client_id = $1", input.ClientID, principal.Subject) {
			return
		}
		c.Next()
	}
}

// RequireAccountOwner checks the owning client's agent before permitting an
// account deletion.
func RequireAccountOwner(db *sql.DB, parameter string) gin.HandlerFunc {
	return func(c *gin.Context) {
		principal, ok := principalFromGin(c)
		if !ok {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "authentication required"})
			return
		}
		if principal.Role == "ADMIN" {
			c.Next()
			return
		}
		accountID := c.Param(parameter)
		if !uuidPathPattern.MatchString(accountID) {
			c.Next()
			return
		}
		account, err := orm.FindAccount(c.Request.Context(), db, accountID)
		if errors.Is(err, sql.ErrNoRows) {
			c.AbortWithStatusJSON(http.StatusNotFound, gin.H{"error": "resource not found"})
			return
		}
		if err != nil {
			c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{"error": "unable to authorize resource access"})
			return
		}
		client, err := orm.FindClient(c.Request.Context(), db, account.ClientID)
		if errors.Is(err, sql.ErrNoRows) {
			c.AbortWithStatusJSON(http.StatusNotFound, gin.H{"error": "resource not found"})
			return
		}
		if err != nil {
			c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{"error": "unable to authorize resource access"})
			return
		}
		if client.CreatedByAgentID != principal.Subject {
			c.AbortWithStatusJSON(http.StatusNotFound, gin.H{"error": "resource not found"})
			return
		}
		c.Next()
	}
}

func ownsClient(c *gin.Context, db *sql.DB, query, id, subject string) bool {
	var owner string
	err := db.QueryRowContext(c.Request.Context(), query, id).Scan(&owner)
	if errors.Is(err, sql.ErrNoRows) || (err == nil && owner != subject) {
		c.AbortWithStatusJSON(http.StatusNotFound, gin.H{"error": "resource not found"})
		return false
	}
	if err != nil {
		c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{"error": "unable to authorize resource access"})
		return false
	}
	return true
}

func principalFromGin(c *gin.Context) (Principal, bool) {
	value, exists := c.Get("principal")
	if !exists {
		return Principal{}, false
	}
	principal, ok := value.(Principal)
	return principal, ok
}
