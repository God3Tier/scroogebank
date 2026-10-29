package routers

import (
	"log"
	"net/http"
	"time"
	"uuid"

	"receiver/src/internal/models"
	"receiver/src/internal/repository"
	"receiver/src/serverd/accounts"
	"receiver/src/serverd/security"

	"github.com/gin-gonic/gin"
)

const (
	// maxWindowDays is one day more than the dashboard allows, so the
	// dashboard's own limit is always the one a user meets.
	maxWindowDays = 366
)

// transactionStats serves GET /aggregates/transactions/stats?from&to. The
// scope comes from the verified caller: every transaction for an admin, and
// for an agent only the accounts account-service says they own (ADR-0064
// decision 2).
func transactionStats(stats repository.TransactionStats, directory accounts.Directory) gin.HandlerFunc {
	return func(c *gin.Context) {
		query, problem := window(c.Query("from"), c.Query("to"))
		if problem != "" {
			c.JSON(http.StatusBadRequest, gin.H{"error": problem})
			return
		}
		principal, ok := security.PrincipalFromGin(c)
		if !ok {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "authentication required"})
			return
		}
		if principal.Role == "ADMIN" {
			query.All = true
		} else {
			owned, err := directory.OwnedAccountIDs(c.Request.Context(), security.AccessToken(c))
			if !accessAllowed(c, err) {
				return
			}
			if query.AccountIDs, err = normalise(owned); err != nil {
				log.Printf("account-service returned an account ID that is no UUID: %v", err)
				c.JSON(http.StatusBadGateway, gin.H{"error": "could not check the caller's accounts"})
				return
			}
		}
		result, err := stats.TransactionStats(c.Request.Context(), query)
		if err != nil {
			log.Printf("transaction stats failed: %v", err)
			c.JSON(http.StatusInternalServerError, gin.H{"error": "could not compute transaction figures"})
			return
		}
		c.JSON(http.StatusOK, result)
	}
}

// window checks the dates: both given, forwards, and at most 366 days
// counting both ends.
func window(fromValue, toValue string) (models.TransactionStatsQuery, string) {
	from, fromErr := time.Parse(time.DateOnly, fromValue)
	to, toErr := time.Parse(time.DateOnly, toValue)
	if fromErr != nil || toErr != nil {
		return models.TransactionStatsQuery{}, "from and to must be dates (YYYY-MM-DD)"
	}
	if to.Before(from) || to.Sub(from) >= maxWindowDays*24*time.Hour {
		return models.TransactionStatsQuery{}, "the window must run forwards and span at most 366 days"
	}
	return models.TransactionStatsQuery{From: from, To: to}, ""
}

// normalise returns the IDs in the lower-case form stored on transactions.
// An empty list stays empty, not nil: an agent with no accounts gets zeros.
func normalise(ids []string) ([]string, error) {
	normalised := make([]string, 0, len(ids))
	for _, id := range ids {
		parsed, err := uuid.Parse(id)
		if err != nil {
			return nil, err
		}
		normalised = append(normalised, parsed.String())
	}
	return normalised, nil
}
