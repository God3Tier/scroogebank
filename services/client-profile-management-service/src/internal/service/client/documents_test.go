package client

import (
	"bytes"
	"context"
	"testing"

	"client-profile-management-service/src/internal/documents"
	clienterrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	repomock "client-profile-management-service/src/internal/repository/client"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/require"
)

// Synthetic files: each is only the start of its format, never a real
// identity document.
var (
	syntheticJPEG = []byte("\xff\xd8\xff\xe0\x00\x10JFIF\x00synthetic")
	syntheticPNG  = []byte("\x89PNG\r\n\x1a\n\x00\x00\x00\x0dIHDRsynthetic")
	syntheticPDF  = []byte("%PDF-1.7\n% synthetic\n")
)

func TestUploadDocumentAcceptsJPEGPNGAndPDF(t *testing.T) {
	ctx := context.Background()
	tests := map[string]struct {
		content  []byte
		wantType string
	}{
		"JPEG": {syntheticJPEG, "image/jpeg"},
		"PNG":  {syntheticPNG, "image/png"},
		"PDF":  {syntheticPDF, "application/pdf"},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			repo := repomock.NewMockRepository(t)
			repo.On("CreateDocument", ctx, mock.MatchedBy(func(d models.ClientDocument) bool {
				return d.ClientID == "client-123" && d.ContentType == test.wantType && d.SizeBytes == len(test.content) &&
					d.UploadedByAgentID == "agent-1" && d.DocumentID != "" && !d.UploadedAt.IsZero()
			}), test.content).Return(nil).Once()

			document, err := NewClientService(repo, nil, nil).UploadDocument(ctx, "client-123", "agent-1", test.content)

			require.NoError(t, err)
			assert.Equal(t, test.wantType, document.ContentType)
			assert.False(t, document.VerificationEvidence, "uploading is never a verification")
		})
	}
}

func TestUploadDocumentRefusesOtherFiles(t *testing.T) {
	tests := map[string]struct {
		content []byte
		wantErr error
	}{
		"HTML named .png":  {[]byte("<html><script>alert(1)</script></html>"), clienterrors.ErrUnsupportedDocument},
		"SVG":              {[]byte(`<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>`), clienterrors.ErrUnsupportedDocument},
		"plain text":       {[]byte("synthetic NRIC S0000000A"), clienterrors.ErrUnsupportedDocument},
		"GIF":              {[]byte("GIF89a synthetic"), clienterrors.ErrUnsupportedDocument},
		"an empty file":    {nil, clienterrors.ErrBadInput},
		"larger than 5 MB": {append(append([]byte(nil), syntheticPNG...), bytes.Repeat([]byte{0}, documents.MaxSize)...), clienterrors.ErrDocumentTooLarge},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			repo := repomock.NewMockRepository(t)

			_, err := NewClientService(repo, nil, nil).UploadDocument(context.Background(), "client-123", "agent-1", test.content)

			assert.ErrorIs(t, err, test.wantErr)
			repo.AssertNotCalled(t, "CreateDocument", mock.Anything, mock.Anything, mock.Anything)
		})
	}
}

func TestUploadDocumentAtExactlyTheLimit(t *testing.T) {
	ctx := context.Background()
	content := append(append([]byte(nil), syntheticPNG...), bytes.Repeat([]byte{0}, documents.MaxSize-len(syntheticPNG))...)
	repo := repomock.NewMockRepository(t)
	repo.On("CreateDocument", ctx, mock.Anything, content).Return(nil).Once()

	_, err := NewClientService(repo, nil, nil).UploadDocument(ctx, "client-123", "agent-1", content)

	assert.NoError(t, err)
}

func TestGetDocumentTreatsAMalformedIDAsMissing(t *testing.T) {
	repo := repomock.NewMockRepository(t)

	_, _, err := NewClientService(repo, nil, nil).GetDocument(context.Background(), "client-123", "not-a-document-id")

	assert.ErrorIs(t, err, clienterrors.ErrDocumentNotFound)
}
