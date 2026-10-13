package auditnote

import (
	"context"
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestNotesKeepOnlyDistinctUUIDs(t *testing.T) {
	ctx, notes := WithNotes(context.Background())

	AddDocumentIDs(ctx, "0199f1d2-0000-7000-8000-000000000001", "Alice Tan", "")
	AddDocumentIDs(ctx, " 0199F1D2-0000-7000-8000-000000000001 ", "0199f1d2-0000-7000-8000-000000000002")

	assert.Equal(t, []string{
		"0199f1d2-0000-7000-8000-000000000001",
		"0199f1d2-0000-7000-8000-000000000002",
	}, notes.DocumentIDs())
}

func TestAddDocumentIDsWithoutNotesDoesNothing(t *testing.T) {
	assert.NotPanics(t, func() {
		AddDocumentIDs(context.Background(), "0199f1d2-0000-7000-8000-000000000001")
		MarkWithoutDocument(context.Background())
	})
}

func TestNotesRecordAVerificationWithoutADocument(t *testing.T) {
	ctx, notes := WithNotes(context.Background())
	assert.False(t, notes.WithoutDocument())

	MarkWithoutDocument(ctx)

	assert.True(t, notes.WithoutDocument())
}
