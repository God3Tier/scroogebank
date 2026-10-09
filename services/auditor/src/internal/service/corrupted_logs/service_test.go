package corrupted_log

import (
	"auditor/src/internal/models"
	"context"
	"errors"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type fakeRepository struct {
	logs      []models.CorruptedAuditLog
	getErr    error
	insertErr error
	deleteErr error
	inserted  models.CorruptedAuditLog
	deletedID string
}

func (f *fakeRepository) GetCorruptedLogs(context.Context) ([]models.CorruptedAuditLog, error) {
	return f.logs, f.getErr
}

func (f *fakeRepository) InsertCorruptedLog(_ context.Context, raw models.CorruptedAuditLog) error {
	f.inserted = raw
	return f.insertErr
}

func (f *fakeRepository) DeleteCorruptedLog(_ context.Context, id string) error {
	f.deletedID = id
	return f.deleteErr
}

func TestGetCorruptedAuditLog(t *testing.T) {
	databaseErr := errors.New("database unavailable")
	want := []models.CorruptedAuditLog{{ID: "log-1"}}
	tests := map[string]struct {
		logs    []models.CorruptedAuditLog
		repoErr error
		want    []models.CorruptedAuditLog
		wantErr error
	}{
		"returns repository logs":     {logs: want, want: want},
		"preserves repository errors": {repoErr: databaseErr, wantErr: databaseErr},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			svc := New(&fakeRepository{logs: test.logs, getErr: test.repoErr})
			actual, err := svc.GetCorruptedAuditLog(context.Background())
			if test.wantErr != nil {
				require.ErrorIs(t, err, test.wantErr)
				return
			}
			require.NoError(t, err)
			assert.Equal(t, test.want, actual)
		})
	}
}

func TestInsertCorruptedAuditLog(t *testing.T) {
	input := models.CorruptedAuditLog{ID: "log-2"}
	databaseErr := errors.New("database unavailable")
	tests := map[string]struct {
		repoErr error
		wantErr error
	}{
		"inserts record":              {},
		"preserves repository errors": {repoErr: databaseErr, wantErr: databaseErr},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			repo := &fakeRepository{insertErr: test.repoErr}
			err := New(repo).InsertCorruptedAuditLog(context.Background(), input)
			assert.Equal(t, input, repo.inserted)
			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				return
			}
			assert.NoError(t, err)
		})
	}
}

func TestDeleteCorruptedAuditLog(t *testing.T) {
	const id = "log-3"
	databaseErr := errors.New("database unavailable")
	tests := map[string]struct {
		repoErr error
		wantErr error
	}{
		"deletes record":              {},
		"preserves repository errors": {repoErr: databaseErr, wantErr: databaseErr},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			repo := &fakeRepository{deleteErr: test.repoErr}
			err := New(repo).DeleteCorruptedAuditLog(context.Background(), id)
			assert.Equal(t, id, repo.deletedID)
			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				return
			}
			assert.NoError(t, err)
		})
	}
}
