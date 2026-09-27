package serverd

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

func newHandler() http.Handler {
	router := gin.New()
	router.GET("/health", healthHandler)
	return router
}

func healthHandler(context *gin.Context) {
	context.JSON(http.StatusOK, gin.H{"status": "ok"})
}
