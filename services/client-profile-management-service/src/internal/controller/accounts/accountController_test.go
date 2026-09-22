package accounts

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"uuid"

	clienterrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
)

type fakeAccountService struct {
	createErr error
	deleteErr error
}

func (f *fakeAccountService) CreateAccount(ctx context.Context, account *models.Account) error {
	return f.createErr
}

func (f *fakeAccountService) DeleteAccount(ctx context.Context, accountID string) error {
	return f.deleteErr
}

func TestAccountControllerCreateAccount_BadJSON(t *testing.T) {
	ctrl := &accountController{svc: &fakeAccountService{}}
	req := httptest.NewRequest(http.MethodPost, "/accounts", strings.NewReader("{not-json"))
	res := httptest.NewRecorder()

	ctrl.CreateAccount(res, req)
	if res.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want %d", res.Code, http.StatusBadRequest)
	}
}

func TestAccountControllerCreateAccount_Conflict(t *testing.T) {
	ctrl := &accountController{svc: &fakeAccountService{createErr: clienterrors.ErrDuplicateAccount}}
	body := `{"client_id":"` + uuid.New().String() + `","account_type":"savings","status":"active","opening_date":"2024-01-01T00:00:00Z","initial_deposit":1000,"currency":"USD","branch_id":"B001"}`
	req := httptest.NewRequest(http.MethodPost, "/accounts", strings.NewReader(body))
	res := httptest.NewRecorder()

	ctrl.CreateAccount(res, req)
	if res.Code != http.StatusConflict {
		t.Fatalf("status = %d, want %d", res.Code, http.StatusConflict)
	}
}

func TestAccountControllerCreateAccount_Success(t *testing.T) {
	ctrl := &accountController{svc: &fakeAccountService{}}
	body := `{"client_id":"` + uuid.New().String() + `","account_type":"savings","status":"active","opening_date":"2024-01-01T00:00:00Z","initial_deposit":1000,"currency":"USD","branch_id":"B001"}`
	req := httptest.NewRequest(http.MethodPost, "/accounts", strings.NewReader(body))
	res := httptest.NewRecorder()

	ctrl.CreateAccount(res, req)
	if res.Code != http.StatusCreated {
		t.Fatalf("status = %d, want %d", res.Code, http.StatusCreated)
	}
}

func TestAccountControllerDeleteAccount_Success(t *testing.T) {
	ctrl := &accountController{svc: &fakeAccountService{}}
	req := httptest.NewRequest(http.MethodDelete, "/accounts?id="+ uuid.New().String(), nil)
	res := httptest.NewRecorder()

	ctrl.DeleteAccount(res, req)
	if res.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", res.Code, http.StatusOK)
	}
}

func TestAccountControllerDeleteAccount_NotFound(t *testing.T) {
	ctrl := &accountController{svc: &fakeAccountService{deleteErr: clienterrors.ErrNotFound}}
	req := httptest.NewRequest(http.MethodDelete, "/accounts?id="+uuid.New().String(), nil)
	res := httptest.NewRecorder()

	ctrl.DeleteAccount(res, req)
	if res.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want %d", res.Code, http.StatusNotFound)
	}
}
