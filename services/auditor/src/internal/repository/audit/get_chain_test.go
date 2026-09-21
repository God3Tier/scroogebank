package audit

import (
	"context"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestGetChain(t *testing.T) {
	db := openTestDB(t)
	loadTestFixture(t, db, "get_chain.sql")

	actual, err := New(db).GetChain(context.Background())

	require.NoError(t, err)
	require.Len(t, actual, 3)
	assert.Equal(t, int64(1), actual[0].SequenceNo)
	assert.Equal(t, "", actual[0].ParentHash)
	assert.Equal(t, int64(2), actual[1].SequenceNo)
	assert.Equal(t, "chain-hash-1", actual[1].ParentHash)
	assert.Equal(t, int64(3), actual[2].SequenceNo)
	assert.Equal(t, "chain-hash-2", actual[2].ParentHash)
	assert.Equal(t, "chain-hash-3", actual[2].Hash)
}
