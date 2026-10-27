package client

import (
	"context"
	"database/sql"
	"errors"
	"testing"

	clienterrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const (
	reassignFrom   = "agent-reassign-from"
	reassignTo     = "agent-reassign-to"
	reassignOther  = "agent-reassign-other"
	reassignFirst  = "00000000-0000-0000-0000-000000000961"
	reassignSecond = "00000000-0000-0000-0000-000000000962"
	reassignThird  = "00000000-0000-0000-0000-000000000963"
	reassignGone   = "00000000-0000-0000-0000-000000000964"
)

// seedReassignment stores two clients of reassignFrom, one of reassignOther
// and a deleted one of reassignFrom.
func seedReassignment(t *testing.T) (*sql.DB, clientRepository) {
	t.Helper()
	db, repo, _ := newTestRepository(t)
	_, err := db.Exec(`DELETE FROM clients WHERE assigned_agent_id IN ($1, $2, $3)`, reassignFrom, reassignTo, reassignOther)
	require.NoError(t, err)
	seedOwnedClient(t, db, repo, reassignFirst, "reassign-961@example.test", "+6560000961", reassignFrom, 4)
	seedOwnedClient(t, db, repo, reassignSecond, "reassign-962@example.test", "+6560000962", reassignFrom, 3)
	seedOwnedClient(t, db, repo, reassignThird, "reassign-963@example.test", "+6560000963", reassignOther, 2)
	seedOwnedClient(t, db, repo, reassignGone, "reassign-964@example.test", "+6560000964", reassignFrom, 1)
	markDeleted(t, db, reassignGone)
	return db, repo
}

func agentOf(t *testing.T, db *sql.DB, clientID string) string {
	t.Helper()
	var agent string
	require.NoError(t, db.QueryRow(`SELECT assigned_agent_id FROM clients WHERE client_id = $1`, clientID).Scan(&agent))
	return agent
}

// recordMoves stands in for account-service and records what it was asked to move.
func recordMoves(err error) (*[][]string, func(context.Context, []string) error) {
	calls := &[][]string{}
	return calls, func(_ context.Context, ids []string) error {
		*calls = append(*calls, ids)
		return err
	}
}

func TestReassignEveryClientOfAnAgent(t *testing.T) {
	db, repo := seedReassignment(t)
	calls, move := recordMoves(nil)

	moved, err := repo.ReassignClients(context.Background(), reassignFrom, nil, reassignTo, move)

	require.NoError(t, err)
	assert.Equal(t, []models.Reassignment{
		{ClientID: reassignFirst, FromAgentID: reassignFrom, ToAgentID: reassignTo},
		{ClientID: reassignSecond, FromAgentID: reassignFrom, ToAgentID: reassignTo},
	}, moved)
	assert.Equal(t, [][]string{{reassignFirst, reassignSecond}}, *calls, "the accounts move with the clients")
	assert.Equal(t, reassignTo, agentOf(t, db, reassignFirst))
	assert.Equal(t, reassignTo, agentOf(t, db, reassignSecond))
	assert.Equal(t, reassignOther, agentOf(t, db, reassignThird))
	assert.Equal(t, reassignFrom, agentOf(t, db, reassignGone), "a deleted client is never moved")
}

func TestReassignChosenClients(t *testing.T) {
	db, repo := seedReassignment(t)
	calls, move := recordMoves(nil)

	moved, err := repo.ReassignClients(context.Background(), "", []string{reassignThird, reassignFirst}, reassignTo, move)

	require.NoError(t, err)
	assert.Equal(t, []models.Reassignment{
		{ClientID: reassignFirst, FromAgentID: reassignFrom, ToAgentID: reassignTo},
		{ClientID: reassignThird, FromAgentID: reassignOther, ToAgentID: reassignTo},
	}, moved)
	assert.Equal(t, [][]string{{reassignFirst, reassignThird}}, *calls)
	assert.Equal(t, reassignFrom, agentOf(t, db, reassignSecond))
}

func TestReassignLeavesOutClientsTheAgentAlreadyHas(t *testing.T) {
	db, repo := seedReassignment(t)
	calls, move := recordMoves(nil)

	moved, err := repo.ReassignClients(context.Background(), "", []string{reassignThird}, reassignOther, move)

	require.NoError(t, err)
	assert.Empty(t, moved)
	assert.Empty(t, *calls, "account-service is not asked when nothing moves")
	assert.Equal(t, reassignOther, agentOf(t, db, reassignThird))
}

func TestReassignRefusesAMissingOrDeletedClient(t *testing.T) {
	for name, missing := range map[string]string{"missing": "00000000-0000-0000-0000-000000000969", "deleted": reassignGone} {
		t.Run(name, func(t *testing.T) {
			db, repo := seedReassignment(t)
			calls, move := recordMoves(nil)

			_, err := repo.ReassignClients(context.Background(), "", []string{reassignFirst, missing}, reassignTo, move)

			assert.ErrorIs(t, err, clienterrors.ErrClientNotFound)
			assert.Empty(t, *calls)
			assert.Equal(t, reassignFrom, agentOf(t, db, reassignFirst), "nothing moves")
		})
	}
}

func TestReassignMovesNothingWhenTheAccountsCannotMove(t *testing.T) {
	db, repo := seedReassignment(t)
	_, move := recordMoves(clienterrors.ErrAccountServiceUnavailable)

	_, err := repo.ReassignClients(context.Background(), reassignFrom, nil, reassignTo, move)

	assert.True(t, errors.Is(err, clienterrors.ErrAccountServiceUnavailable))
	assert.Equal(t, reassignFrom, agentOf(t, db, reassignFirst))
	assert.Equal(t, reassignFrom, agentOf(t, db, reassignSecond))
}
