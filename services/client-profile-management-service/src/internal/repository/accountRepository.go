package repository

import (
	"context"
	"database/sql"
	"fmt"
	"time"
	"uuid"

	clienterrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"

	"github.com/aarondl/sqlboiler/v4/boil"
)

type accountRepository struct {
}

func NewAccountRepository() AccountRepository {
	return &accountRepository{}
}

func (r *accountRepository) CreateAccount(ctx context.Context, exec boil.ContextExecutor, account *models.Account) error {
	if account == nil {
		return fmt.Errorf("%w: account is required", clienterrors.ErrBadInput)
	}

	if account.ID == uuid.Nil() {
		account.ID = uuid.NewV7()
	}
	if account.CreatedAt.IsZero() {
		account.CreatedAt = time.Now().UTC()
	}
	if account.UpdatedAt.IsZero() {
		account.UpdatedAt = time.Now().UTC()
	}

	clientExists := false
	if err := exec.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM clients WHERE client_id = $1)`, account.ClientID).Scan(&clientExists); err != nil {
		return err
	}
	if !clientExists {
		return clienterrors.ErrClientNotFound
	}

	duplicateExists := false
	if err := exec.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM accounts WHERE client_id = $1 AND account_type = $2)`, account.ClientID, account.Type).Scan(&duplicateExists); err != nil {
		if err == sql.ErrNoRows {
			duplicateExists = false
		} else {
			return err
		}
	}
	if duplicateExists {
		return clienterrors.ErrDuplicateAccount
	}

	_, err := exec.ExecContext(ctx,
		`INSERT INTO accounts (id, client_id, account_type, status, opening_date, initial_deposit, currency, branch_id, created_at, updated_at)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)`,
		account.ID,
		account.ClientID,
		account.Type,
		account.Status,
		account.OpeningDate,
		account.InitialDeposit,
		account.Currency,
		account.BranchID,
		account.CreatedAt,
		account.UpdatedAt,
	)
	return err
}

func (r *accountRepository) DeleteAccount(ctx context.Context, exec boil.ContextExecutor, accountID string) error {
	id, err := uuid.Parse(accountID)
	if err != nil {
		return fmt.Errorf("%w: %s", clienterrors.ErrInvalidAccountID, accountID)
	}

	result, err := exec.ExecContext(ctx, `DELETE FROM accounts WHERE id = $1`, id)
	if err != nil {
		return err
	}

	rowsAffected, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if rowsAffected == 0 {
		return clienterrors.ErrNotFound
	}
	return nil
}
