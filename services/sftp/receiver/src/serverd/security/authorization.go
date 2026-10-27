package security

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

const (
	principalKey = "principal"
	tokenKey     = "access_token"
)

// SetCaller records the verified caller and their token for the handlers.
// Only Middleware, and tests standing in for it, call it.
func SetCaller(c *gin.Context, principal Principal, token string) {
	c.Set(principalKey, principal)
	c.Set(tokenKey, token)
}

// PrincipalFromGin returns the caller verified by CognitoVerifier.Middleware.
func PrincipalFromGin(c *gin.Context) (Principal, bool) {
	value, exists := c.Get(principalKey)
	if !exists {
		return Principal{}, false
	}
	principal, ok := value.(Principal)
	return principal, ok
}

// AccessToken returns the verified caller's token, to forward to
// account-service. It is never logged or stored.
func AccessToken(c *gin.Context) string {
	return c.GetString(tokenKey)
}

// RequireAdmin is for the list of every account's transactions, which agents
// may not read (ADR-0043).
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
