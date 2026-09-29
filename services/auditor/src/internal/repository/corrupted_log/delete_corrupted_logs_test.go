package corrupted_log

import (
	"context"
	"database/sql"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestDeleteCorruptedLog(t *testing.T) {
	tests := map[string]struct {
		fixture string
		id      string
		wantErr error
	}{
		"deletes an existing record": {
			fixture: "delete_corrupted_log.sql",
			id:      "00000000-0000-0000-0000-000000000104",
		},
		"returns no rows when the record is missing": {
			fixture: "insert_corrupted_log.sql",
			id:      "00000000-0000-0000-0000-000000000105",
			wantErr: sql.ErrNoRows,
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			db := openTestDB(t)
			loadTestFixture(t, db, test.fixture)

			err := (impl{db: db}).DeleteCorruptedLog(context.Background(), test.id)

			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				return
			}
			require.NoError(t, err)
			var count int
			require.NoError(t, db.QueryRow(`SELECT count(*) FROM corrupted_audit_log WHERE id = $1`, test.id).Scan(&count))
			assert.Zero(t, count)
		})
	}
}
