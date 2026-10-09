package routers

import (
	aggregatecontroller "client-profile-management-service/src/internal/controller/aggregate"
	clientcontroller "client-profile-management-service/src/internal/controller/client"
	"client-profile-management-service/src/internal/models"
	"client-profile-management-service/src/internal/security"
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

// RegisterRoutes serves client profiles only. Accounts moved to account-service
// (ADR-0017, ADR-0035).
func RegisterRoutes(router *gin.Engine, clients clientcontroller.ClientController, aggregates aggregatecontroller.Controller, db *sql.DB, authenticate, auditLogger gin.HandlerFunc) {
	// The business dashboard's figures, fetched with the caller's own token.
	// Outside /api, so API Gateway never routes them, and not audited here:
	// the dashboard audits each view (ADR-0039).
	aggregate := router.Group("/aggregates")
	aggregate.Use(authenticate)
	aggregate.Use(security.RequireCRMRole())
	aggregate.GET("/clients/stats", withCaller(aggregates.ClientStats))
	aggregate.GET("/clients/action-items", withCaller(aggregates.ActionItems))
	aggregate.GET("/clients/team-comparison", withCaller(aggregates.TeamComparison))

	api := router.Group("/api")
	api.Use(authenticate)
	api.Use(security.RequireCRMRole())

	client := api.Group("/clients")
	client.Use(auditLogger)
	{
		client.POST("", adaptHandler(clients.CreateClientProfile, ""))
		client.POST("/:clientId/verify", security.RequireClientOwner(db, "clientId"), adaptHandler(clients.VerifyClientIdentity, "clientId"))
		client.PUT("/:clientId", security.RequireClientOwner(db, "clientId"), adaptHandler(clients.UpdateClientInformation, "clientId"))
		client.GET("/:clientId", security.RequireClientOwner(db, "clientId"), adaptHandler(clients.GetClientProfile, "clientId"))
		client.DELETE("/:clientId", security.RequireClientOwner(db, "clientId"), adaptHandler(clients.DeleteClientProfile, "clientId"))
	}
}

// withCaller hands the verified caller to the controller explicitly. Aggregates
// are scoped by that caller, so it must never come from request data.
func withCaller(handler func(http.ResponseWriter, *http.Request, models.Caller)) gin.HandlerFunc {
	return func(c *gin.Context) {
		principal, ok := security.PrincipalFromGin(c)
		if !ok {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "authentication required"})
			return
		}
		handler(c.Writer, c.Request, models.Caller{Subject: principal.Subject, Admin: principal.Role == "ADMIN"})
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
