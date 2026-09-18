package routers

import (
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

func RegisterRoutes(router *gin.Engine) {
	router.GET("/health", func(c *gin.Context){
		c.JSON(http.StatusOK, gin.H{
			"status" : "OK",
		})
	})
}