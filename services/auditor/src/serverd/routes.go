package main

import (
	corruptedlog "auditor/src/internal/controller/corrupted_log"
	"net/http"

	"github.com/gin-gonic/gin"
)

func newHandler(corruptedLogs corruptedlog.Controller) http.Handler {
	router := gin.New()
	router.GET("/health", healthHandler)
	router.GET("/api/corrupted-logs", adaptHandler(corruptedLogs.GetCorruptedAuditLog, ""))
	router.POST("/api/corrupted-logs", adaptHandler(corruptedLogs.InsertCorruptedAuditLog, ""))
	router.DELETE("/api/corrupted-logs/:id", adaptHandler(corruptedLogs.DeleteCorruptedAuditLog, "id"))
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
