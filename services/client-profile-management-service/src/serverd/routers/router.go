package routers

import (
	clientcontroller "client-profile-management-service/src/internal/controller/client"
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
func RegisterRoutes(router *gin.Engine, clients clientcontroller.ClientController, db *sql.DB, authenticate, auditLogger gin.HandlerFunc) {
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
