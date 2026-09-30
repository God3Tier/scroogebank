package routers

import (
	"net/http"
	"strings"

	"receiver/src/internal/repository"

	"github.com/gin-gonic/gin"
)

func RegisterRoutes(router *gin.Engine, transactions repository.Transaction) {
	router.GET("/health", func(c *gin.Context) {
		c.JSON(200, gin.H{"status": "ok"})
	})
	router.GET("/api/accounts/:accountId/transactions", getTransactions(transactions))
}

func getTransactions(transactionRepository repository.Transaction) gin.HandlerFunc {
	return func(c *gin.Context) {
		accountID := strings.TrimSpace(c.Param("accountId"))
		if accountID == "" {
			c.JSON(http.StatusBadRequest, gin.H{"error": "accountId is required"})
			return
		}

		transactions, err := transactionRepository.GetTransactionsByAccountID(c.Request.Context(), accountID)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "could not retrieve transactions"})
			return
		}
		c.JSON(http.StatusOK, transactions)
	}
}
