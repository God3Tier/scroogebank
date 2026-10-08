package security

import (
	"database/sql"
	"errors"
	"net/http"
	"regexp"

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
//
// account-service relies on this rule: it forwards the caller's token to
// GET /api/clients/{id} before creating an account, and treats 404 as "no
// such client for this caller".
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
