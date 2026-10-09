package routers

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"receiver/src/internal/models"
	"receiver/src/internal/repository/orm"

	"github.com/gin-gonic/gin"
)

type transactionRepositoryStub struct {
	transactions orm.TransactionSlice
	err          error
	accountID    string
}

func (s *transactionRepositoryStub) SaveTransaction(context.Context, models.Transaction) error {
	return nil
}

func (s *transactionRepositoryStub) GetTransactionsByAccountID(_ context.Context, accountID string) (orm.TransactionSlice, error) {
	s.accountID = accountID
	return s.transactions, s.err
}

func TestGetTransactions(t *testing.T) {
	gin.SetMode(gin.TestMode)

	tests := []struct {
		name       string
		accountID  string
		repo       *transactionRepositoryStub
		wantStatus int
		wantBody   string
		wantCalled bool
	}{
		{
			name:       "returns transactions for account",
			accountID:  "account-123",
			repo:       &transactionRepositoryStub{transactions: orm.TransactionSlice{}},
			wantStatus: http.StatusOK,
			wantBody:   "[]",
			wantCalled: true,
		},
		{
			name:       "returns empty array when no transactions exist",
			accountID:  "account-empty",
			repo:       &transactionRepositoryStub{transactions: orm.TransactionSlice{}},
			wantStatus: http.StatusOK,
			wantBody:   "[]",
			wantCalled: true,
		},
		{
			name:       "returns internal error when repository fails",
			accountID:  "account-123",
			repo:       &transactionRepositoryStub{err: errors.New("database unavailable")},
			wantStatus: http.StatusInternalServerError,
			wantBody:   `{"error":"could not retrieve transactions"}`,
			wantCalled: true,
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			router := gin.New()
			RegisterRoutes(router, test.repo, nil)
			request := httptest.NewRequest(http.MethodGet, "/api/accounts/"+test.accountID+"/transactions", nil)
			response := httptest.NewRecorder()
			router.ServeHTTP(response, request)

			if response.Code != test.wantStatus {
				t.Fatalf("status = %d, want %d; body=%s", response.Code, test.wantStatus, response.Body.String())
			}
			if response.Body.String() != test.wantBody {
				t.Errorf("body = %q, want %q", response.Body.String(), test.wantBody)
			}
			if test.wantCalled && test.repo.accountID != test.accountID {
				t.Errorf("repository accountID = %q, want %q", test.repo.accountID, test.accountID)
			}
		})
	}
}
