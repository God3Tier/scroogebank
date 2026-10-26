package routers

import (
	"net/http"
	"strings"

	"receiver/src/internal/repository"

	"github.com/gin-gonic/gin"
)

func RegisterRoutes(router *gin.Engine, transactions repository.Transaction, stats repository.TransactionStats) {
	router.GET("/health", func(c *gin.Context) {
		c.JSON(200, gin.H{"status": "ok"})
	})
	router.GET("/api/accounts/:accountId/transactions", getTransactions(transactions))
	// Every account's transactions; user-service lets only admins through
	// (ADR-0043).
	router.GET("/api/transactions", listTransactions(transactions))
	// Like the route above, private and unauthenticated: user-service checks
	// the caller and chooses the accounts first (ADR-0006, ADR-0039).
	router.POST("/aggregates/transactions/stats", transactionStats(stats))
}

// RegisterClientHealthRoutes mounts the machine-to-machine history API under
// the required scope. It is kept separate so the admin list contract stays
// unchanged.
func RegisterClientHealthRoutes(router *gin.Engine, transactions repository.Transaction, authenticate gin.HandlerFunc) {
	internal := router.Group("/internal/v1")
	internal.Use(authenticate)
	internal.GET("/transactions", listClientHealthTransactions(transactions))
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
