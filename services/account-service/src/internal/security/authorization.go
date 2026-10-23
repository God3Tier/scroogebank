package security

import (
	"database/sql"
	"errors"
	"net/http"
	"regexp"

	orm "account-service/src/internal/repository/orm"

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

// RequireAgent keeps admins to reading accounts: every route that changes an
// account is for agents only (ADR-0059).
func RequireAgent() gin.HandlerFunc {
	return func(c *gin.Context) {
		principal, ok := PrincipalFromGin(c)
		if !ok || principal.Role != "AGENT" {
			c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "admins have read-only access to client data"})
			return
		}
		c.Next()
	}
}

// RequireAccountOwner limits agents to accounts they own. Admins can read
// every account; the routes that change one also need RequireAgent. The owner
// is stored on the account itself (copied from
// client-profile-management-service when the account was created), so this
// needs no call to another service. Missing, deleted and out-of-scope accounts
// all return 404 so callers cannot enumerate another agent's account IDs.
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

// The generated finder skips deleted accounts (ADR-0036).
func ownsAccount(c *gin.Context, db *sql.DB, accountID, subject string) bool {
	account, err := orm.FindAccount(c.Request.Context(), db, accountID, orm.AccountColumns.OwnerAgentID)
	if errors.Is(err, sql.ErrNoRows) || (err == nil && account.OwnerAgentID != subject) {
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
