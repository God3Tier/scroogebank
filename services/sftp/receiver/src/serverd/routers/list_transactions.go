package routers

import (
	"encoding/base64"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"

	"receiver/src/internal/models"
	"receiver/src/internal/repository"
	"receiver/src/internal/repository/orm"

	"github.com/gin-gonic/gin"
)

const (
	defaultListSize = 20
	maxListSize     = 100
)

var listStatuses = map[string]bool{"Completed": true, "Pending": true, "Failed": true}

// transactionPage is one page of GET /api/transactions, in the same shape as
// user-service's activity feed: NextCursor is null on the last page.
type transactionPage struct {
	Content    orm.TransactionSlice `json:"content"`
	NextCursor *string              `json:"nextCursor"`
}

// listTransactions serves GET /api/transactions?cursor&size&status, every
// account's transactions for an admin. Like the per-account route it is
// private and unauthenticated: user-service checks the caller (ADR-0043).
func listTransactions(transactionRepository repository.Transaction) gin.HandlerFunc {
	return func(c *gin.Context) {
		query, problem := listQuery(c)
		if problem != "" {
			c.JSON(http.StatusBadRequest, gin.H{"error": problem})
			return
		}
		size := query.Limit
		// One row more than the page shows whether another page follows.
		query.Limit = size + 1

		transactions, err := transactionRepository.ListTransactions(c.Request.Context(), query)
		if err != nil {
			log.Printf("list transactions failed: %v", err)
			c.JSON(http.StatusInternalServerError, gin.H{"error": "could not retrieve transactions"})
			return
		}

		page := transactionPage{Content: transactions}
		if len(transactions) > size {
			page.Content = transactions[:size]
			last := page.Content[size-1]
			next := encodeCursor(models.TransactionCursor{Date: last.TransactionDate, ID: last.TransactionID})
			page.NextCursor = &next
		}
		c.JSON(http.StatusOK, page)
	}
}

func listQuery(c *gin.Context) (models.TransactionListQuery, string) {
	query := models.TransactionListQuery{Limit: defaultListSize}
	if value := c.Query("size"); value != "" {
		size, err := strconv.Atoi(value)
		if err != nil || size < 1 || size > maxListSize {
			return query, "size must be a whole number from 1 to 100"
		}
		query.Limit = size
	}
	if status := c.Query("status"); status != "" {
		if !listStatuses[status] {
			return query, "status must be Completed, Pending or Failed"
		}
		query.Status = status
	}
	if value := c.Query("cursor"); value != "" {
		cursor, ok := decodeCursor(value)
		if !ok {
			return query, "cursor is not one this service returned"
		}
		query.After = &cursor
	}
	return query, ""
}

// A cursor is the last row's date and ID, base64url-encoded so it is opaque
// to callers and safe in a query string.
func encodeCursor(cursor models.TransactionCursor) string {
	raw := cursor.Date.UTC().Format(time.RFC3339Nano) + "|" + cursor.ID
	return base64.RawURLEncoding.EncodeToString([]byte(raw))
}

func decodeCursor(value string) (models.TransactionCursor, bool) {
	raw, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil {
		return models.TransactionCursor{}, false
	}
	date, id, found := strings.Cut(string(raw), "|")
	if !found || id == "" {
		return models.TransactionCursor{}, false
	}
	parsed, err := time.Parse(time.RFC3339Nano, date)
	if err != nil {
		return models.TransactionCursor{}, false
	}
	return models.TransactionCursor{Date: parsed, ID: id}, true
}
