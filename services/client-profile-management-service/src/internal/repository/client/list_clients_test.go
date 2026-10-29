package client

import (
	"context"
	"database/sql"
	"testing"

	"client-profile-management-service/src/internal/encryption/encryptiontest"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const (
	listAgentA = "00000000-0000-0000-0000-000000000911"
	listAgentB = "00000000-0000-0000-0000-000000000912"
)

// seedOwnedClient stores a client as seedClient does, created by agentID
// minutesAgo minutes ago, so the order of the list is known.
func seedOwnedClient(t *testing.T, db *sql.DB, repo clientRepository, id, email, phone, agentID string, minutesAgo int) {
	t.Helper()
	seedClient(t, db, repo, id, email, phone)
	_, err := db.Exec(`UPDATE clients SET assigned_agent_id = $1, created_at = now() - make_interval(mins => $2) WHERE client_id = $3`,
		agentID, minutesAgo, id)
	require.NoError(t, err)
}

func clientIDs(t *testing.T, repo clientRepository, ownerID string, page, size int) ([]string, int64) {
	t.Helper()
	clients, total, err := repo.ListClients(context.Background(), ownerID, page, size)
	require.NoError(t, err)
	ids := make([]string, 0, len(clients))
	for _, client := range clients {
		ids = append(ids, client.ClientID.String())
	}
	return ids, total
}

func TestListClients(t *testing.T) {
	const (
		first   = "00000000-0000-0000-0000-000000000301"
		second  = "00000000-0000-0000-0000-000000000302"
		other   = "00000000-0000-0000-0000-000000000303"
		deleted = "00000000-0000-0000-0000-000000000304"
	)
	db, repo, _ := newTestRepository(t)
	// Clear rows an earlier run left with these agents.
	_, err := db.Exec(`DELETE FROM clients WHERE assigned_agent_id IN ($1, $2)`, listAgentA, listAgentB)
	require.NoError(t, err)
	seedOwnedClient(t, db, repo, first, "list-301@example.test", "+6560000301", listAgentA, 3)
	seedOwnedClient(t, db, repo, second, "list-302@example.test", "+6560000302", listAgentA, 2)
	seedOwnedClient(t, db, repo, other, "list-303@example.test", "+6560000303", listAgentB, 1)
	seedOwnedClient(t, db, repo, deleted, "list-304@example.test", "+6560000304", listAgentA, 0)
	markDeleted(t, db, deleted)

	t.Run("an agent gets only their own clients, newest first", func(t *testing.T) {
		ids, total := clientIDs(t, repo, listAgentA, 0, 20)
		assert.Equal(t, []string{second, first}, ids)
		assert.Equal(t, int64(2), total)
	})

	t.Run("pages by size and keeps the total", func(t *testing.T) {
		ids, total := clientIDs(t, repo, listAgentA, 1, 1)
		assert.Equal(t, []string{first}, ids)
		assert.Equal(t, int64(2), total)
	})

	t.Run("a page past the end is empty", func(t *testing.T) {
		ids, total := clientIDs(t, repo, listAgentA, 5, 20)
		assert.Empty(t, ids)
		assert.Equal(t, int64(2), total)
	})

	t.Run("no owner lists every agent's clients but not deleted ones", func(t *testing.T) {
		ids, total := clientIDs(t, repo, "", 0, 100)
		assert.Subset(t, ids, []string{first, second, other})
		assert.NotContains(t, ids, deleted)
		assert.GreaterOrEqual(t, total, int64(3))
	})

	t.Run("returns the decrypted profile", func(t *testing.T) {
		clients, _, err := repo.ListClients(context.Background(), listAgentB, 0, 20)
		require.NoError(t, err)
		require.Len(t, clients, 1)
		assert.Equal(t, "Alice", clients[0].FirstName)
		assert.Equal(t, "list-303@example.test", clients[0].Email)
		assert.Equal(t, listAgentB, clients[0].AssignedAgentID)
	})

	// Last: it re-creates agent B's client with a key only this protector holds.
	t.Run("leaves out a client whose data key is gone", func(t *testing.T) {
		// A protector that holds only agent B's client's key, as if the
		// others had been erased (ADR-0030).
		protector := encryptiontest.New()
		onlyOther := clientRepository{db: db, protector: protector}
		seedOwnedClient(t, db, onlyOther, other, "list-303@example.test", "+6560000303", listAgentB, 1)

		ids, total := clientIDs(t, onlyOther, listAgentA, 0, 20)
		assert.Empty(t, ids)
		assert.Equal(t, int64(2), total)
		ids, _ = clientIDs(t, onlyOther, listAgentB, 0, 20)
		assert.Equal(t, []string{other}, ids)
	})
}
