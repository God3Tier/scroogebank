package routers

import (
	"errors"
	"log"
	"net/http"
	"uuid"

	"receiver/src/internal/repository"
	"receiver/src/serverd/accounts"
	"receiver/src/serverd/audit"
	"receiver/src/serverd/security"

	"github.com/gin-gonic/gin"
)

// RegisterRoutes serves transaction reads to callers authenticate verifies
// (ADR-0064). Ownership is account-service's answer to the caller's own
// token; no route takes an agent or an account list from the caller.
func RegisterRoutes(router *gin.Engine, transactions repository.Transaction, stats repository.TransactionStats,
	directory accounts.Directory, authenticate gin.HandlerFunc, saveAudit audit.Save) {
	router.GET("/health", func(c *gin.Context) {
		c.JSON(200, gin.H{"status": "ok"})
	})

	api := router.Group("/api")
	api.Use(authenticate)
	// Each read is audited, refused ones included; the audit runs around the
	// role check so an agent refused the admin list is recorded too.
	api.GET("/accounts/:accountId/transactions",
		audit.Read(saveAudit, "ACCOUNT", "accountId"), getTransactions(transactions, directory))
	// Every account's transactions, for admins only (ADR-0043).
	api.GET("/transactions",
		audit.Read(saveAudit, "TRANSACTION", ""), security.RequireAdmin(), listTransactions(transactions))

	// The business dashboard's figures, fetched with the caller's own token.
	// Outside /api, so API Gateway never routes it, and not audited here: the
	// dashboard audits each view (ADR-0039 decision 6).
	aggregates := router.Group("/aggregates")
	aggregates.Use(authenticate)
	aggregates.GET("/transactions/stats", transactionStats(stats, directory))
}

func getTransactions(transactionRepository repository.Transaction, directory accounts.Directory) gin.HandlerFunc {
	return func(c *gin.Context) {
		accountID, err := uuid.Parse(c.Param("accountId"))
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "accountId must be a UUID"})
			return
		}
		if !accessAllowed(c, directory.CheckAccess(c.Request.Context(), accountID.String(), security.AccessToken(c))) {
			return
		}

		transactions, err := transactionRepository.GetTransactionsByAccountID(c.Request.Context(), accountID.String())
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "could not retrieve transactions"})
			return
		}
		c.JSON(http.StatusOK, transactions)
	}
}

// accessAllowed answers the request itself when account-service refused the
// caller or could not be asked, and reports whether the handler may go on.
func accessAllowed(c *gin.Context, err error) bool {
	var refused accounts.Refused
	switch {
	case err == nil:
		return true
	case errors.As(err, &refused):
		// The same answer account-service gave, so an agent cannot tell
		// another agent's account from a missing one.
		c.JSON(refused.Status, gin.H{"error": refusalMessage(refused.Status)})
	default:
		log.Printf("account check failed: %v", err)
		c.JSON(http.StatusBadGateway, gin.H{"error": "could not check the caller's accounts"})
	}
	return false
}

func refusalMessage(status int) string {
	switch status {
	case http.StatusNotFound:
		return "resource not found"
	case http.StatusUnauthorized:
		return "invalid access token"
	default:
		return "access denied"
	}
}
