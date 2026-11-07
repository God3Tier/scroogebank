package account

import (
	"context"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestSetOwner(t *testing.T) {
	db := openTestDB(t)
	loadTestFixture(t, db, "account_owner.sql")
	owner := func(accountID string) string {
		var got string
		require.NoError(t, db.QueryRow(`SELECT owner_agent_id FROM accounts WHERE id = $1`, accountID).Scan(&got))
		return got
	}

	updated, err := NewAccountRepository(db).SetOwner(context.Background(),
		[]string{"00000000-0000-0000-0000-000000000241", "00000000-0000-0000-0000-000000000242"}, "agent-new-241")

	require.NoError(t, err)
	assert.Equal(t, int64(3), updated)
	assert.Equal(t, "agent-new-241", owner("00000000-0000-0000-0000-000000000251"))
	assert.Equal(t, "agent-new-241", owner("00000000-0000-0000-0000-000000000252"), "a deleted account follows its client")
	assert.Equal(t, "agent-new-241", owner("00000000-0000-0000-0000-000000000253"))
	assert.Equal(t, "agent-other-243", owner("00000000-0000-0000-0000-000000000254"), "another client's account is untouched")
}

func TestSetOwnerWithoutClientsChangesNothing(t *testing.T) {
	updated, err := NewAccountRepository(openTestDB(t)).SetOwner(context.Background(), nil, "agent-new-241")

	require.NoError(t, err)
	assert.Zero(t, updated)
}
