package routers

import (
	"log"
	"net/http"
	"time"
	"uuid"

	"receiver/src/internal/models"
	"receiver/src/internal/repository"

	"github.com/gin-gonic/gin"
)

const (
	// maxWindowDays is one day more than the dashboard allows, so the
	// dashboard's own limit is always the one a user meets.
	maxWindowDays = 366
	// maxStatsAccounts bounds one agent's account list.
	maxStatsAccounts = 20000
	maxStatsBody     = 2 << 20
)

// transactionStatsRequest names the transactions to total: every one with
// "all": true, or those of "accountIds". It must say which, so a request that
// forgets the account list is refused instead of reading as "everything".
type transactionStatsRequest struct {
	From       string    `json:"from"`
	To         string    `json:"to"`
	AccountIDs *[]string `json:"accountIds"`
	All        bool      `json:"all"`
}

// transactionStats serves POST /aggregates/transactions/stats.
func transactionStats(stats repository.TransactionStats) gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, maxStatsBody)
		var request transactionStatsRequest
		if err := c.ShouldBindJSON(&request); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "body must be JSON with from, to and either all or accountIds"})
			return
		}
		query, problem := request.query()
		if problem != "" {
			c.JSON(http.StatusBadRequest, gin.H{"error": problem})
			return
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

func (r transactionStatsRequest) query() (models.TransactionStatsQuery, string) {
	from, fromErr := time.Parse(time.DateOnly, r.From)
	to, toErr := time.Parse(time.DateOnly, r.To)
	if fromErr != nil || toErr != nil {
		return models.TransactionStatsQuery{}, "from and to must be dates (YYYY-MM-DD)"
	}
	if to.Before(from) || to.Sub(from) >= maxWindowDays*24*time.Hour {
		return models.TransactionStatsQuery{}, "the window must run forwards and span at most 366 days"
	}
	if r.All == (r.AccountIDs != nil) {
		return models.TransactionStatsQuery{}, "give exactly one of all: true or accountIds"
	}
	query := models.TransactionStatsQuery{From: from, To: to, All: r.All}
	if r.AccountIDs != nil {
		if len(*r.AccountIDs) > maxStatsAccounts {
			return models.TransactionStatsQuery{}, "too many accountIds"
		}
		query.AccountIDs = make([]string, 0, len(*r.AccountIDs))
		for _, id := range *r.AccountIDs {
			parsed, err := uuid.Parse(id)
			if err != nil {
				return models.TransactionStatsQuery{}, "accountIds must be UUIDs"
			}
			query.AccountIDs = append(query.AccountIDs, parsed.String())
		}
	}
	return query, ""
}
