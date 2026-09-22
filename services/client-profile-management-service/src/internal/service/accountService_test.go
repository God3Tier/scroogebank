package service

import (
	"context"
	"errors"
	"testing"
	"time"
	"uuid"

	clienterrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"

	"github.com/aarondl/sqlboiler/v4/boil"
)

type stubAccountRepo struct {
	createErr  error
	deleteErr  error
	created    *models.Account
	deletedID  string
	createCall int
	deleteCall int
}

func (s *stubAccountRepo) CreateAccount(ctx context.Context, exec boil.ContextExecutor, account *models.Account) error {
	s.created = account
	s.createCall++
	return s.createErr
}

func (s *stubAccountRepo) DeleteAccount(ctx context.Context, exec boil.ContextExecutor, accountID string) error {
	s.deletedID = accountID
	s.deleteCall++
	return s.deleteErr
}

func TestAccountServiceCreateAccount_RejectsInvalidAccount(t *testing.T) {
	repo := &stubAccountRepo{}
	svc := &accountService{repo: repo}

	account := &models.Account{
		ClientID:       uuid.Nil(),
		Type:           "bogus",
		Status:         "active",
		OpeningDate:    time.Now().UTC(),
		InitialDeposit: 1000,
		Currency:       "USD",
		BranchID:       "B001",
	}

	if err := svc.CreateAccount(context.Background(), account); !errors.Is(err, clienterrors.ErrBadInput) {
		t.Fatalf("CreateAccount() error = %v, want ErrBadInput", err)
	}
	if repo.createCall != 0 {
		t.Fatalf("CreateAccount() should not call repo for invalid account")
	}
}

func TestAccountServiceCreateAccount_ValidAccountCallsRepo(t *testing.T) {
	repo := &stubAccountRepo{}
	svc := &accountService{repo: repo}
	account := &models.Account{
		ClientID:       uuid.New(),
		Type:           "savings",
		Status:         "active",
		OpeningDate:    time.Now().UTC(),
		InitialDeposit: 1000,
		Currency:       "USD",
		BranchID:       "B001",
	}

	if err := svc.CreateAccount(context.Background(), account); err != nil {
		t.Fatalf("CreateAccount() unexpected error: %v", err)
	}
	if repo.createCall != 1 {
		t.Fatalf("CreateAccount() repo call count = %d, want 1", repo.createCall)
	}
	if repo.created == nil || repo.created.ID == uuid.Nil() {
		t.Fatal("CreateAccount() should assign an ID before repository call")
	}
}

func TestAccountServiceDeleteAccount_RejectsEmptyID(t *testing.T) {
	repo := &stubAccountRepo{}
	svc := &accountService{repo: repo}

	err := svc.DeleteAccount(context.Background(), "")
	if !errors.Is(err, clienterrors.ErrBadInput) {
		t.Fatalf("DeleteAccount() error = %v, want ErrBadInput", err)
	}
	if repo.deleteCall != 0 {
		t.Fatalf("DeleteAccount() should not call repo for empty id")
	}
}
