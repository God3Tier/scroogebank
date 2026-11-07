package account

import (
	"context"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const listedClientID = "00000000-0000-0000-0000-000000000203"

func TestListAccountsByClient(t *testing.T) {
	db := openTestDB(t)
	loadTestFixture(t, db, "account_list.sql")

	got, err := NewAccountRepository(db).ListAccountsByClient(context.Background(), listedClientID)

	require.NoError(t, err)
	ids := make([]string, 0, len(got))
	for _, account := range got {
		ids = append(ids, account.ID)
	}
	// Every undeleted account of this client, oldest first, and none of
	// another client's. Deleted account 0234 is left out.
	assert.Equal(t, []string{
		"00000000-0000-0000-0000-000000000231",
		"00000000-0000-0000-0000-000000000232",
		"00000000-0000-0000-0000-000000000233",
	}, ids)
}

func TestListAccountsByClientAndOwner(t *testing.T) {
	db := openTestDB(t)
	loadTestFixture(t, db, "account_list.sql")

	got, err := NewAccountRepository(db).ListAccountsByClientAndOwner(context.Background(), listedClientID, "agent-sub-a")

	require.NoError(t, err)
	require.Len(t, got, 2)
	for _, account := range got {
		assert.Equal(t, listedClientID, account.ClientID)
		assert.Equal(t, "agent-sub-a", account.OwnerAgentID)
	}
}

func TestFindAccountsByIDs(t *testing.T) {
	db := openTestDB(t)
	loadTestFixture(t, db, "account_list.sql")
	repo := NewAccountRepository(db)

	got, err := repo.FindAccountsByIDs(context.Background(), []string{
		"00000000-0000-0000-0000-000000000231",
		"00000000-0000-0000-0000-000000000234", // deleted
		"00000000-0000-0000-0000-000000000299", // unknown
		"00000000-0000-0000-0000-000000000241",
	})

	require.NoError(t, err)
	ids := make([]string, 0, len(got))
	for _, account := range got {
		ids = append(ids, account.ID)
	}
	assert.Equal(t, []string{"00000000-0000-0000-0000-000000000231", "00000000-0000-0000-0000-000000000241"}, ids)

	none, err := repo.FindAccountsByIDs(context.Background(), nil)
	require.NoError(t, err)
	assert.NotNil(t, none)
	assert.Empty(t, none)
}

func TestListAccountsReturnsAnEmptySliceNotNil(t *testing.T) {
	db := openTestDB(t)
	loadTestFixture(t, db, "account_list.sql")

	got, err := NewAccountRepository(db).ListAccountsByClient(context.Background(), "00000000-0000-0000-0000-000000000299")

	require.NoError(t, err)
	assert.NotNil(t, got)
	assert.Empty(t, got)
}
