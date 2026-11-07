package security

import (
	"database/sql"
	"errors"
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
		principal, ok := PrincipalFromGin(c)
		if !ok || (principal.Role != "ADMIN" && principal.Role != "AGENT") {
			c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "ADMIN or AGENT role required"})
			return
		}
		c.Next()
	}
}

// RequireAgent keeps admins to reading client data: every route that changes a
// client or its documents is for agents only (ADR-0059).
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

// RequireAdmin is for the one admin write on client data: reassigning clients
// to another agent (ADR-0060).
func RequireAdmin() gin.HandlerFunc {
	return func(c *gin.Context) {
		principal, ok := PrincipalFromGin(c)
		if !ok || principal.Role != "ADMIN" {
			c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "ADMIN role required"})
			return
		}
		c.Next()
	}
}

// RequireClientOwner limits agent access to their own client profiles. Admins
// can read every profile; the routes that change one also need RequireAgent.
// Missing, deleted and out-of-scope profiles all return 404 so callers cannot
// enumerate another agent's client IDs.
//
// account-service relies on this rule: it forwards the caller's token to
// GET /api/clients/{id} before creating an account, and treats 404 as "no
// such client for this caller".
func RequireClientOwner(db *sql.DB, parameter string) gin.HandlerFunc {
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
		clientID := c.Param(parameter)
		if !uuidPathPattern.MatchString(clientID) {
			c.Next() // Let the controller return its normal malformed-ID response.
			return
		}
		if !ownsClient(c, db, clientID, principal.Subject) {
			return
		}
		c.Next()
	}
}

// The generated finder skips deleted clients (ADR-0036).
func ownsClient(c *gin.Context, db *sql.DB, clientID, subject string) bool {
	client, err := orm.FindClient(c.Request.Context(), db, clientID, orm.ClientColumns.AssignedAgentID)
	if errors.Is(err, sql.ErrNoRows) || (err == nil && client.AssignedAgentID != subject) {
		c.AbortWithStatusJSON(http.StatusNotFound, gin.H{"error": "resource not found"})
		return false
	}
	if err != nil {
		c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{"error": "unable to authorize resource access"})
		return false
	}
	return true
}

func PrincipalFromGin(c *gin.Context) (Principal, bool) {
	value, exists := c.Get("principal")
	if !exists {
		return Principal{}, false
	}
	principal, ok := value.(Principal)
	return principal, ok
}
