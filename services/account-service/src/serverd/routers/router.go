package routers

import (
	accountcontroller "account-service/src/internal/controller/account"
	aggregatecontroller "account-service/src/internal/controller/aggregate"
	"account-service/src/internal/models"
	"account-service/src/internal/security"
	"database/sql"
	"net/http"

	"github.com/gin-gonic/gin"
)

/*
	List of HTTP status codes used in the application
	http.StatusOK = 200
	http.StatusCreated = 201
	http.StatusAccepted = 202
	http.StatusNoContent = 204
	http.StatusBadRequest = 400
	http.StatusUnauthorized = 401
	http.StatusForbidden = 403
	http.StatusNotFound = 404
	http.StatusInternalServerError = 500
	http.StatusNotImplemented = 501
	http.StatusBadGateway = 502
	http.StatusServiceUnavailable = 503
*/

func RegisterRoutes(router *gin.Engine, accounts accountcontroller.AccountController, aggregates aggregatecontroller.Controller, db *sql.DB, authenticate, authenticateService, auditLogger gin.HandlerFunc) {
	// The business dashboard's figures, and the account IDs user-service
	// scopes an agent's transaction figures by, fetched with the caller's own
	// user token. Outside /api, so API Gateway never routes them, and not
	// audited here: the dashboard audits each view (ADR-0039).
	aggregate := router.Group("/aggregates")
	aggregate.Use(authenticate)
	aggregate.Use(security.RequireCRMRole())
	aggregate.GET("/accounts/stats", withCaller(aggregates.AccountStats))
	aggregate.GET("/accounts/ids", withCaller(aggregates.OwnedAccountIDs))

	// Service-to-service routes take client-credentials tokens, never user
	// tokens, and sit outside /api so API Gateway never routes them (ADR-0037).
	// Not audited: a lookup changes nothing, and the import audits each file.
	internal := router.Group("/internal")
	internal.Use(authenticateService)
	internal.POST("/accounts/lookup", adaptHandler(accounts.LookupAccounts, ""))

	api := router.Group("/api")
	api.Use(authenticate)
	api.Use(security.RequireCRMRole())

	account := api.Group("/accounts")
	account.Use(auditLogger)
	{
		// Ownership of the client is checked by client-profile-management-
		// service inside CreateAccount, using the caller's token.
		account.POST("", adaptHandler(accounts.CreateAccount, ""))
		account.GET("", listAccounts(accounts))
		account.GET("/:accountId", security.RequireAccountOwner(db, "accountId"), adaptHandler(accounts.GetAccount, "accountId"))
		account.DELETE("/:accountId", security.RequireAccountOwner(db, "accountId"), adaptHandler(accounts.DeleteAccount, "accountId"))
	}
}

// listAccounts hands the verified caller to the controller explicitly. The
// list is scoped by that caller, so it must never come from request data.
func listAccounts(accounts accountcontroller.AccountController) gin.HandlerFunc {
	return withCaller(accounts.ListAccounts)
}

// withCaller hands the verified caller to the controller explicitly, for
// handlers scoped by that caller.
func withCaller(handler func(http.ResponseWriter, *http.Request, models.Caller)) gin.HandlerFunc {
	return func(c *gin.Context) {
		principal, ok := security.PrincipalFromGin(c)
		if !ok {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "authentication required"})
			return
		}
		handler(c.Writer, c.Request, models.Caller{
			Subject: principal.Subject,
			Admin:   principal.Role == "ADMIN",
		})
	}
}

// adaptHandler lets the existing net/http controllers serve Gin routes. The
// route IDs are copied into the query parameter used by those controllers.
func adaptHandler(handler func(http.ResponseWriter, *http.Request), routeID string) gin.HandlerFunc {
	return func(c *gin.Context) {
		req := c.Request
		if routeID != "" {
			urlCopy := *req.URL
			query := urlCopy.Query()
			query.Set("id", c.Param(routeID))
			urlCopy.RawQuery = query.Encode()
			requestCopy := req.Clone(req.Context())
			requestCopy.URL = &urlCopy
			req = requestCopy
		}
		handler(c.Writer, req)
	}
}
