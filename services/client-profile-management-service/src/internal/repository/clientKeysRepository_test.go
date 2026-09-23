package repository

import (
	"context"
	"database/sql"
	"errors"
	"testing"

	clienterrors "client-profile-management-service/src/internal/errors"

	"github.com/DATA-DOG/go-sqlmock"
)

func TestClientKeysRepositoryGetClientKey(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatalf("sqlmock.New() error = %v", err)
	}
	defer db.Close()

	mock.ExpectQuery(`SELECT key_value FROM client_keys WHERE client_id = \$1`).
		WithArgs("client-1").
		WillReturnRows(sqlmock.NewRows([]string{"key_value"}).AddRow("01234567890123456789012345678901"))

	key, err := NewClientKeysRepository(db).GetClientKey(context.Background(), "client-1")
	if err != nil {
		t.Fatalf("GetClientKey() error = %v", err)
	}
	if string(key) != "01234567890123456789012345678901" {
		t.Fatalf("GetClientKey() = %q", key)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Fatalf("unmet SQL expectations: %v", err)
	}
}

func TestClientKeysRepositoryGetClientKeyNotFound(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatalf("sqlmock.New() error = %v", err)
	}
	defer db.Close()

	mock.ExpectQuery(`SELECT key_value FROM client_keys WHERE client_id = \$1`).
		WithArgs("missing").
		WillReturnError(sql.ErrNoRows)

	_, err = NewClientKeysRepository(db).GetClientKey(context.Background(), "missing")
	if !errors.Is(err, clienterrors.ErrNotFound) {
		t.Fatalf("GetClientKey() error = %v, want ErrNotFound", err)
	}
}
