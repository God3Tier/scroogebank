package client

import (
	"context"
	"database/sql"
	"testing"
	"uuid"

	clienterrors "client-profile-management-service/src/internal/errors"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const verifyingAgent = "agent-verifier"

// verificationOf reads what clients-db holds about the client's verification.
func verificationOf(t *testing.T, db *sql.DB, clientID string) (status string, by sql.NullString, at sql.NullTime, withDocuments sql.NullBool) {
	t.Helper()
	require.NoError(t, db.QueryRow(`SELECT verification_status, verified_by_agent_id, verified_at, verified_with_documents FROM clients WHERE client_id = $1`, clientID).
		Scan(&status, &by, &at, &withDocuments))
	return status, by, at, withDocuments
}

func evidenceOf(t *testing.T, db *sql.DB, documentID string) bool {
	t.Helper()
	var evidence bool
	require.NoError(t, db.QueryRow(`SELECT verification_evidence FROM client_documents WHERE document_id = $1`, documentID).Scan(&evidence))
	return evidence
}

func TestVerifyRecordsWhoWhenAndWhichDocuments(t *testing.T) {
	db, repo, _ := newTestRepository(t)
	seedClient(t, db, repo, documentClientID, "alice-101@example.test", "+6560000101")
	checked := seedDocument(t, repo, documentClientID)
	notChecked := seedDocument(t, repo, documentClientID)

	require.NoError(t, repo.VerifyClientIdentity(context.Background(), documentClientID, verifyingAgent, []string{checked}))

	status, by, at, withDocuments := verificationOf(t, db, documentClientID)
	assert.Equal(t, "VERIFIED", status)
	assert.Equal(t, verifyingAgent, by.String)
	assert.True(t, at.Valid)
	assert.Equal(t, sql.NullBool{Bool: true, Valid: true}, withDocuments)
	assert.True(t, evidenceOf(t, db, checked))
	assert.False(t, evidenceOf(t, db, notChecked))

	profile, err := repo.GetClientProfile(context.Background(), documentClientID)
	require.NoError(t, err)
	assert.Equal(t, verifyingAgent, profile.VerifiedByAgentID)
	require.NotNil(t, profile.VerifiedAt)
	require.NotNil(t, profile.VerifiedWithDocuments)
	assert.True(t, *profile.VerifiedWithDocuments)
}

func TestVerifyWithSeveralDocumentsMarksEachOne(t *testing.T) {
	db, repo, _ := newTestRepository(t)
	seedClient(t, db, repo, documentClientID, "alice-101@example.test", "+6560000101")
	front := seedDocument(t, repo, documentClientID)
	back := seedDocument(t, repo, documentClientID)
	notChecked := seedDocument(t, repo, documentClientID)

	require.NoError(t, repo.VerifyClientIdentity(context.Background(), documentClientID, verifyingAgent, []string{front, back}))

	_, _, _, withDocuments := verificationOf(t, db, documentClientID)
	assert.True(t, withDocuments.Bool)
	list, err := repo.ListDocuments(context.Background(), documentClientID)
	require.NoError(t, err)
	evidence := map[string]bool{}
	for _, doc := range list {
		evidence[doc.DocumentID] = doc.VerificationEvidence
	}
	assert.Equal(t, map[string]bool{front: true, back: true, notChecked: false}, evidence)
}

func TestVerifyWithoutADocumentIsRecordedAsSuch(t *testing.T) {
	db, repo, _ := newTestRepository(t)
	seedClient(t, db, repo, documentClientID, "alice-101@example.test", "+6560000101")
	uploaded := seedDocument(t, repo, documentClientID)

	require.NoError(t, repo.VerifyClientIdentity(context.Background(), documentClientID, verifyingAgent, []string{}))

	status, by, at, withDocuments := verificationOf(t, db, documentClientID)
	assert.Equal(t, "VERIFIED", status)
	assert.Equal(t, verifyingAgent, by.String)
	assert.True(t, at.Valid)
	assert.Equal(t, sql.NullBool{Bool: false, Valid: true}, withDocuments, "false, not unknown")
	assert.False(t, evidenceOf(t, db, uploaded), "a document that was not named is not evidence")

	profile, err := repo.GetClientProfile(context.Background(), documentClientID)
	require.NoError(t, err)
	require.NotNil(t, profile.VerifiedWithDocuments)
	assert.False(t, *profile.VerifiedWithDocuments)
}

func TestAnUnverifiedClientHasNoVerificationDetails(t *testing.T) {
	db, repo, _ := newTestRepository(t)
	seedClient(t, db, repo, documentClientID, "alice-101@example.test", "+6560000101")

	profile, err := repo.GetClientProfile(context.Background(), documentClientID)

	require.NoError(t, err)
	assert.Empty(t, profile.VerifiedByAgentID)
	assert.Nil(t, profile.VerifiedAt)
	assert.Nil(t, profile.VerifiedWithDocuments)
}

func TestVerifyRefusesAClientThatIsAlreadyVerified(t *testing.T) {
	// "verified" is what clients verified before ADR-0041 hold.
	for _, earlier := range []string{"VERIFIED", "verified"} {
		t.Run(earlier, func(t *testing.T) {
			db, repo, _ := newTestRepository(t)
			seedClient(t, db, repo, documentClientID, "alice-101@example.test", "+6560000101")
			document := seedDocument(t, repo, documentClientID)
			_, err := db.Exec(`UPDATE clients SET verification_status = $1 WHERE client_id = $2`, earlier, documentClientID)
			require.NoError(t, err)

			err = repo.VerifyClientIdentity(context.Background(), documentClientID, verifyingAgent, []string{document})

			assert.ErrorIs(t, err, clienterrors.ErrAlreadyVerified)
			assert.False(t, evidenceOf(t, db, document))
		})
	}
}

func TestVerifyRefusesDocumentsThatAreNotTheClients(t *testing.T) {
	db, repo, _ := newTestRepository(t)
	seedClient(t, db, repo, documentClientID, "alice-101@example.test", "+6560000101")
	seedClient(t, db, repo, otherClientID, "bob-102@example.test", "+6560000102")
	own := seedDocument(t, repo, documentClientID)
	others := seedDocument(t, repo, otherClientID)

	tests := map[string][]string{
		"another client's document":      {own, others},
		"a document that does not exist": {own, uuid.New().String()},
	}
	for name, documentIDs := range tests {
		t.Run(name, func(t *testing.T) {
			err := repo.VerifyClientIdentity(context.Background(), documentClientID, verifyingAgent, documentIDs)

			assert.ErrorIs(t, err, clienterrors.ErrDocumentNotFound)
			status, by, _, _ := verificationOf(t, db, documentClientID)
			assert.Equal(t, "UNVERIFIED", status, "nothing changes")
			assert.False(t, by.Valid)
			assert.False(t, evidenceOf(t, db, own))
		})
	}
}

func TestVerifyAMissingOrDeletedClient(t *testing.T) {
	for name, deleted := range map[string]bool{"missing": false, "deleted": true} {
		t.Run(name, func(t *testing.T) {
			db, repo, _ := newTestRepository(t)
			seedClient(t, db, repo, documentClientID, "alice-101@example.test", "+6560000101")
			document := seedDocument(t, repo, documentClientID)
			clientID := "00000000-0000-0000-0000-000000000199"
			if deleted {
				markDeleted(t, db, documentClientID)
				clientID = documentClientID
			}

			err := repo.VerifyClientIdentity(context.Background(), clientID, verifyingAgent, []string{document})

			assert.ErrorIs(t, err, clienterrors.ErrClientNotFound)
		})
	}
}

func TestAnUpdateLeavesTheVerificationAsItIs(t *testing.T) {
	db, repo, _ := newTestRepository(t)
	profile := seedClient(t, db, repo, documentClientID, "alice-101@example.test", "+6560000101")
	document := seedDocument(t, repo, documentClientID)
	require.NoError(t, repo.VerifyClientIdentity(context.Background(), documentClientID, verifyingAgent, []string{document}))

	profile.City = "Updated City"
	require.NoError(t, repo.UpdateClientInformation(context.Background(), documentClientID, profile))

	status, by, at, withDocuments := verificationOf(t, db, documentClientID)
	assert.Equal(t, "VERIFIED", status)
	assert.Equal(t, verifyingAgent, by.String)
	assert.True(t, at.Valid)
	assert.True(t, withDocuments.Bool)
}
