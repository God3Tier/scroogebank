package main

import (
	corruptedlog "auditor/src/internal/controller/corrupted_log"
	"auditor/src/internal/security"
	"net/http"

	"github.com/gin-gonic/gin"
)

// newHandler serves /health to anyone, and the corrupted-log endpoints only
// to an admin's access token, which user-service forwards (ADR-0021).
func newHandler(corruptedLogs corruptedlog.Controller, verifier security.Verifier) http.Handler {
	router := gin.New()
	router.GET("/health", healthHandler)
	api := router.Group("/api", security.AdminOnly(verifier))
	api.GET("/corrupted-logs", adaptHandler(corruptedLogs.GetCorruptedAuditLog, ""))
	api.POST("/corrupted-logs", adaptHandler(corruptedLogs.InsertCorruptedAuditLog, ""))
	api.DELETE("/corrupted-logs/:id", adaptHandler(corruptedLogs.DeleteCorruptedAuditLog, "id"))
	return router
}

func healthHandler(context *gin.Context) {
	context.JSON(http.StatusOK, gin.H{"status": "ok"})
}

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
