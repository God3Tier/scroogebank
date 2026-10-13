package client

import (
	"bytes"
	"context"
	"errors"
	"testing"
	"time"
	"uuid"

	"client-profile-management-service/src/internal/documents"
	clienterrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const (
	documentClientID = "00000000-0000-0000-0000-000000000101"
	otherClientID    = "00000000-0000-0000-0000-000000000102"
)

// syntheticPNG is the start of a PNG file followed by text, never a real
// identity document.
var syntheticPNG = append([]byte("\x89PNG\r\n\x1a\n"), []byte("synthetic identity document")...)

// seedDocument stores a synthetic document for the client through the
// repository and returns its ID.
func seedDocument(t *testing.T, repo clientRepository, clientID string) string {
	t.Helper()
	document := models.ClientDocument{
		DocumentID:        uuid.New().String(),
		ClientID:          clientID,
		ContentType:       "image/png",
		SizeBytes:         len(syntheticPNG),
		UploadedByAgentID: "agent-uploader",
		UploadedAt:        time.Now().UTC(),
	}
	require.NoError(t, repo.CreateDocument(context.Background(), document, syntheticPNG))
	return document.DocumentID
}

func TestDocumentsAreStoredSealedAndReadBack(t *testing.T) {
	ctx := context.Background()
	db, repo, _ := newTestRepository(t)
	seedClient(t, db, repo, documentClientID, "alice-101@example.test", "+6560000101")

	id := seedDocument(t, repo, documentClientID)

	stored, ok := documentStore(repo).Object(documents.ObjectKey(documentClientID, id))
	require.True(t, ok, "the document is stored under the client and document IDs")
	assert.False(t, bytes.Contains(stored, []byte("synthetic identity document")), "only the sealed document is stored")

	list, err := repo.ListDocuments(ctx, documentClientID)
	require.NoError(t, err)
	require.Len(t, list, 1)
	assert.Equal(t, id, list[0].DocumentID)
	assert.Equal(t, "image/png", list[0].ContentType)
	assert.Equal(t, len(syntheticPNG), list[0].SizeBytes)
	assert.Equal(t, "agent-uploader", list[0].UploadedByAgentID)
	assert.False(t, list[0].VerificationEvidence)

	got, content, err := repo.GetDocument(ctx, documentClientID, id)
	require.NoError(t, err)
	assert.Equal(t, list[0], got)
	assert.Equal(t, syntheticPNG, content)
}

func TestDocumentsAreListedNewestFirst(t *testing.T) {
	db, repo, _ := newTestRepository(t)
	seedClient(t, db, repo, documentClientID, "alice-101@example.test", "+6560000101")
	first := seedDocument(t, repo, documentClientID)
	second := seedDocument(t, repo, documentClientID)

	list, err := repo.ListDocuments(context.Background(), documentClientID)

	require.NoError(t, err)
	require.Len(t, list, 2)
	assert.Equal(t, []string{second, first}, []string{list[0].DocumentID, list[1].DocumentID})
}

func TestDocumentsBelongToTheirClient(t *testing.T) {
	ctx := context.Background()
	db, repo, _ := newTestRepository(t)
	seedClient(t, db, repo, documentClientID, "alice-101@example.test", "+6560000101")
	seedClient(t, db, repo, otherClientID, "bob-102@example.test", "+6560000102")
	othersDocument := seedDocument(t, repo, otherClientID)

	_, _, err := repo.GetDocument(ctx, documentClientID, othersDocument)
	assert.ErrorIs(t, err, clienterrors.ErrDocumentNotFound)
	list, err := repo.ListDocuments(ctx, documentClientID)
	require.NoError(t, err)
	assert.Empty(t, list)
}

func TestDocumentsOfAMissingOrDeletedClient(t *testing.T) {
	ctx := context.Background()
	for name, deleted := range map[string]bool{"missing": false, "deleted": true} {
		t.Run(name, func(t *testing.T) {
			db, repo, _ := newTestRepository(t)
			seedClient(t, db, repo, documentClientID, "alice-101@example.test", "+6560000101")
			id := seedDocument(t, repo, documentClientID)
			clientID := "00000000-0000-0000-0000-000000000199"
			if deleted {
				markDeleted(t, db, documentClientID)
				clientID = documentClientID
			}

			err := repo.CreateDocument(ctx, models.ClientDocument{DocumentID: uuid.New().String(), ClientID: clientID}, syntheticPNG)
			assert.ErrorIs(t, err, clienterrors.ErrClientNotFound)
			_, err = repo.ListDocuments(ctx, clientID)
			assert.ErrorIs(t, err, clienterrors.ErrClientNotFound)
			_, _, err = repo.GetDocument(ctx, clientID, id)
			assert.ErrorIs(t, err, clienterrors.ErrClientNotFound)
		})
	}
}

func TestADocumentThatCannotBeStoredIsNotRecorded(t *testing.T) {
	db, repo, _ := newTestRepository(t)
	seedClient(t, db, repo, documentClientID, "alice-101@example.test", "+6560000101")
	documentStore(repo).Err = errors.New("S3 unavailable")

	err := repo.CreateDocument(context.Background(), models.ClientDocument{
		DocumentID: uuid.New().String(), ClientID: documentClientID, ContentType: "image/png",
		SizeBytes: len(syntheticPNG), UploadedByAgentID: "agent-uploader", UploadedAt: time.Now(),
	}, syntheticPNG)

	assert.Error(t, err)
	var rows int
	require.NoError(t, db.QueryRow(`SELECT count(*) FROM client_documents WHERE client_id = $1`, documentClientID).Scan(&rows))
	assert.Zero(t, rows)
}
