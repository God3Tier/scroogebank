package service

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
	"time"
	"uuid"

	clienterrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	"client-profile-management-service/src/internal/repository"
)

var validAccountTypes = map[string]struct{}{
	"checking": {},
	"savings":  {},
	"credit":   {},
	"loan":     {},
	"investment": {},
}

var validAccountStatuses = map[string]struct{}{
	"active":   {},
	"inactive": {},
	"pending":  {},
	"closed":   {},
}

func normalizeAccountInput(s string) string {
	return strings.TrimSpace(strings.ToLower(s))
}

func validateAccount(account *models.Account) error {
	if account == nil {
		return fmt.Errorf("%w: account is required", clienterrors.ErrBadInput)
	}

	if account.ID == uuid.Nil() {
		account.ID = uuid.New()
	}
	if account.ClientID == uuid.Nil() {
		return fmt.Errorf("%w: client_id is required; %w", clienterrors.ErrBadInput, clienterrors.ErrInvalidClientID)
	}

	accountType := normalizeAccountInput(account.Type)
	if accountType == "" {
		return fmt.Errorf("%w: account_type is required; %w", clienterrors.ErrBadInput, clienterrors.ErrInvalidAccountType)
	}
	if _, ok := validAccountTypes[accountType]; !ok {
		return fmt.Errorf("%w: account_type %q is invalid", clienterrors.ErrBadInput, account.Type)
	}
	account.Type = accountType

	status := normalizeAccountInput(account.Status)
	if status == "" {
		account.Status = "active"
	} else {
		if _, ok := validAccountStatuses[status]; !ok {
			return fmt.Errorf("%w: account status %q is invalid", clienterrors.ErrBadInput, account.Status)
		}
		account.Status = status
	}

	if account.OpeningDate.IsZero() {
		return fmt.Errorf("%w: opening_date is required", clienterrors.ErrBadInput)
	}
	if account.InitialDeposit < 0 {
		return fmt.Errorf("%w: initial_deposit cannot be negative", clienterrors.ErrBadInput)
	}

	currency := strings.TrimSpace(strings.ToUpper(account.Currency))
	if currency == "" {
		return fmt.Errorf("%w: currency is required; %w", clienterrors.ErrBadInput, clienterrors.ErrInvalidCurrency)
	}
	if len(currency) != 3 {
		return fmt.Errorf("%w: currency must be a 3-letter ISO code", clienterrors.ErrBadInput)
	}
	account.Currency = currency

	if strings.TrimSpace(account.BranchID) == "" {
		return fmt.Errorf("%w: branch_id is required; %w", clienterrors.ErrBadInput, clienterrors.ErrInvalidBranchID)
	}

	if account.CreatedAt.IsZero() {
		account.CreatedAt = time.Now().UTC()
	}
	if account.UpdatedAt.IsZero() {
		account.UpdatedAt = time.Now().UTC()
	}

	return nil
}

type accountService struct {
	db   *sql.DB
	repo repository.AccountRepository
}

func NewAccountService(db *sql.DB, repo repository.AccountRepository) *accountService {
	return &accountService{db: db, repo: repo}
}

func (s *accountService) CreateAccount(ctx context.Context, account *models.Account) error {
	if err := validateAccount(account); err != nil {
		return err
	}

	return s.repo.CreateAccount(ctx, s.db, account)
}

func (s *accountService) DeleteAccount(ctx context.Context, accountID string) error {
	if strings.TrimSpace(accountID) == "" {
		return fmt.Errorf("%w: account_id is required; %w", clienterrors.ErrBadInput, clienterrors.ErrInvalidAccountID)
	}
	return s.repo.DeleteAccount(ctx, s.db, accountID)
}
