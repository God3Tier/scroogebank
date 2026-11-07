package account

import (
	clienterrors "account-service/src/internal/errors"
	"account-service/src/internal/models"
	"fmt"
	"strings"
	"time"
	"uuid"
)

type CreateAccountInput struct {
	ClientID       uuid.UUID `json:"client_id"`
	Type           string    `json:"account_type"`
	Status         string    `json:"status"`
	OpeningDate    time.Time `json:"opening_date"`
	InitialDeposit float64   `json:"initial_deposit"`
	Currency       string    `json:"currency"`
	BranchID       string    `json:"branch_id"`
}

// AccountIDInput represents the account identifier supplied to a delete
// operation. The handler obtains it from the route parameter.
type AccountIDInput struct {
	AccountID string `uri:"accountId" json:"account_id"`
}

func (account CreateAccountInput) ValidateAccountAndParse() (models.CreateAccountInput, error) {
	if account.ClientID == uuid.Nil() {
		return models.CreateAccountInput{}, fmt.Errorf("%w: client_id is required; %w", clienterrors.ErrBadInput, clienterrors.ErrInvalidClientID)
	}

	accountType := models.AccountType(normalizeAccountInput(account.Type))

	if !accountType.IsValid() {
		return models.CreateAccountInput{}, fmt.Errorf("%w: account_type %q is invalid", clienterrors.ErrBadInput, account.Type)
	}

	status := models.AccountStatus(normalizeAccountInput(account.Status))

	if !status.IsValid() {
		return models.CreateAccountInput{}, fmt.Errorf("%w: account status %q is invalid", clienterrors.ErrBadInput, account.Status)
	}

	if account.InitialDeposit < 0 {
		return models.CreateAccountInput{}, fmt.Errorf("%w: initial_deposit cannot be negative", clienterrors.ErrBadInput)
	}

	currency := strings.TrimSpace(strings.ToUpper(account.Currency))
	if currency == "" {
		return models.CreateAccountInput{}, fmt.Errorf("%w: currency is required; %w", clienterrors.ErrBadInput, clienterrors.ErrInvalidCurrency)
	}

	if len(currency) != 3 {
		return models.CreateAccountInput{}, fmt.Errorf("%w: currency must be a 3-letter ISO code", clienterrors.ErrBadInput)
	}

	if strings.TrimSpace(account.BranchID) == "" {
		return models.CreateAccountInput{}, fmt.Errorf("%w: branch_id is required; %w", clienterrors.ErrBadInput, clienterrors.ErrInvalidBranchID)
	}

	return models.CreateAccountInput{
		ClientID:       account.ClientID,
		Type:           accountType,
		Status:         status,
		InitialDeposit: account.InitialDeposit,
		Currency:       currency,
		BranchID:       account.BranchID,
	}, nil
}

func normalizeAccountInput(s string) string {
	return strings.TrimSpace(strings.ToUpper(s))
}
