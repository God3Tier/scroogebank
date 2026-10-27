package routers

import (
	"encoding/base64"
	"encoding/json"
	"log"
	"net/http"
	"strconv"
	"time"

	"receiver/src/internal/models"
	"receiver/src/internal/repository"

	"github.com/gin-gonic/gin"
)

const (
	defaultHealthPageSize = 100
	maxHealthPageSize     = 500
)

type clientHealthTransaction struct {
	TransactionID     string    `json:"transactionId"`
	ClientID          string    `json:"clientId"`
	AccountID         string    `json:"accountId"`
	Type              string    `json:"type"`
	Amount            string    `json:"amount"`
	Currency          string    `json:"currency"`
	TransactionDate   time.Time `json:"transactionDate"`
	Status            string    `json:"status"`
	ActivityKind      string    `json:"activityKind"`
	SourceFingerprint *string   `json:"sourceFingerprint"`
}

type clientHealthTransactionPage struct {
	SchemaVersion string                    `json:"schemaVersion"`
	Items         []clientHealthTransaction `json:"items"`
	NextCursor    *string                   `json:"nextCursor"`
}

// listClientHealthTransactions serves the private, client-credentials-only
// history API. The auth middleware is installed on this route at registration.
func listClientHealthTransactions(transactionRepository repository.Transaction) gin.HandlerFunc {
	return func(c *gin.Context) {
		query, problem := clientHealthQuery(c)
		if problem != "" {
			c.JSON(http.StatusBadRequest, gin.H{"error": problem})
			return
		}
		size := query.Limit
		query.Limit++
		rows, err := transactionRepository.ListClientHealthTransactions(c.Request.Context(), query)
		if err != nil {
			log.Printf("list Client Health Monitor transactions failed: %v", err)
			c.JSON(http.StatusInternalServerError, gin.H{"error": "could not retrieve transactions"})
			return
		}
		page := clientHealthTransactionPage{SchemaVersion: "1", Items: make([]clientHealthTransaction, 0, min(len(rows), size))}
		if len(rows) > size {
			rows = rows[:size]
			last := rows[len(rows)-1]
			cursor, _ := json.Marshal(models.TransactionCursor{Date: last.TransactionDate, ID: last.TransactionID})
			encoded := base64.RawURLEncoding.EncodeToString(cursor)
			page.NextCursor = &encoded
		}
		for _, row := range rows {
			page.Items = append(page.Items, clientHealthTransaction{
				TransactionID: row.TransactionID, ClientID: row.ClientID, AccountID: row.AccountID,
				Type: row.TransactionType, Amount: row.Amount, Currency: row.Currency,
				TransactionDate: row.TransactionDate, Status: row.Status, ActivityKind: row.ActivityKind,
				SourceFingerprint: row.SourceFingerprint,
			})
		}
		c.JSON(http.StatusOK, page)
	}
}

func clientHealthQuery(c *gin.Context) (models.ClientHealthTransactionQuery, string) {
	query := models.ClientHealthTransactionQuery{}
	from, err := time.Parse(time.RFC3339Nano, c.Query("from"))
	if err != nil {
		return query, "from must be an RFC3339 timestamp"
	}
	to, err := time.Parse(time.RFC3339Nano, c.Query("to"))
	if err != nil || !from.Before(to) {
		return query, "to must be an RFC3339 timestamp after from"
	}
	query.From, query.To = from, to
	query.Limit = defaultHealthPageSize
	if value := c.Query("limit"); value != "" {
		limit, err := strconv.Atoi(value)
		if err != nil || limit < 1 || limit > maxHealthPageSize {
			return query, "limit must be a whole number from 1 to 500"
		}
		query.Limit = limit
	}
	if value := c.Query("cursor"); value != "" {
		cursor, ok := decodeHealthCursor(value)
		if !ok {
			return query, "cursor is invalid"
		}
		query.After = &cursor
	}
	return query, ""
}

func decodeHealthCursor(value string) (models.TransactionCursor, bool) {
	data, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil {
		return models.TransactionCursor{}, false
	}
	var cursor models.TransactionCursor
	if json.Unmarshal(data, &cursor) != nil || cursor.ID == "" || cursor.Date.IsZero() {
		return models.TransactionCursor{}, false
	}
	return cursor, true
}
