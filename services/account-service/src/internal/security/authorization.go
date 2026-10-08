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
		principal, ok := PrincipalFromGin(c)
		if !ok || (principal.Role != "ADMIN" && principal.Role != "AGENT") {
			c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "ADMIN or AGENT role required"})
			return
		}
		c.Next()
	}
}

// RequireAccountOwner limits agents to accounts they own. Admins can access
// every account. The owner is stored on the account itself (copied from
// client-profile-management-service when the account was created), so this
// needs no call to another service. Missing and out-of-scope accounts both
// return 404 so callers cannot enumerate another agent's account IDs.
func RequireAccountOwner(db *sql.DB, parameter string) gin.HandlerFunc {
	return func(c *gin.Context) {
		principal, ok := PrincipalFromGin(c)
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
			c.Next() // Let the controller return its normal malformed-ID response.
			return
		}
		if !ownsAccount(c, db, accountID, principal.Subject) {
			return
		}
		c.Next()
	}
}

func ownsAccount(c *gin.Context, db *sql.DB, accountID, subject string) bool {
	var owner string
	err := db.QueryRowContext(c.Request.Context(), "SELECT owner_agent_id FROM accounts WHERE id = $1", accountID).Scan(&owner)
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

// PrincipalFromGin returns the caller verified by CognitoVerifier.Middleware.
func PrincipalFromGin(c *gin.Context) (Principal, bool) {
	value, exists := c.Get("principal")
	if !exists {
		return Principal{}, false
	}
	principal, ok := value.(Principal)
	return principal, ok
}
