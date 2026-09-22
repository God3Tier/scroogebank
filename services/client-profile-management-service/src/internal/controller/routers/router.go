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
	api := router.Group("/api")

	client := api.Group("/client")
	{
		client.POST("", client.CreateClientProfile)
		client.POST("/:clientId/verify", client.VerifyClientIdentity)
		client.PUT("/:clientId", client.UpdateClientInformation)
		client.GET("/:clientId", client.GetClientProfile)
		client.DELETE("/:clientId", client.DeleteClientProfile)
	}

	account := api.Group("/account")
	{
		account.POST("", account.CreateAccount)
		account.DELETE("/:accountId", account.DeleteAccount)
	}
}
