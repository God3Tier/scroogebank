package client

import (
	"bytes"
	"encoding/json"
	"errors"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"client-profile-management-service/src/internal/contract"
	"client-profile-management-service/src/internal/documents"
	clienterrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	servicemock "client-profile-management-service/src/internal/service/client"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/require"
)

const testDocumentID = "0199f1d2-0000-7000-8000-000000000001"

var syntheticPNG = []byte("\x89PNG\r\n\x1a\nsynthetic")

// uploadRequest builds a multipart upload with content in the given field.
func uploadRequest(t *testing.T, field string, content []byte) *http.Request {
	t.Helper()
	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	part, err := writer.CreateFormFile(field, "Alice Tan NRIC.png")
	require.NoError(t, err)
	_, err = part.Write(content)
	require.NoError(t, err)
	require.NoError(t, writer.Close())
	req := httptest.NewRequest(http.MethodPost, "/api/clients/documents?id=client-123", &body)
	req.Header.Set("Content-Type", writer.FormDataContentType())
	req.Header.Set("X-Agent-ID", "agent-1")
	return req
}

func TestUploadClientDocument(t *testing.T) {
	stored := models.ClientDocument{DocumentID: testDocumentID, ClientID: "client-123", ContentType: "image/png",
		SizeBytes: len(syntheticPNG), UploadedByAgentID: "agent-1", UploadedAt: time.Now().UTC()}
	tests := map[string]struct {
		serviceErr error
		wantStatus int
	}{
		"stores the document":           {wantStatus: http.StatusCreated},
		"maps missing client":           {serviceErr: clienterrors.ErrClientNotFound, wantStatus: http.StatusNotFound},
		"maps an unsupported type":      {serviceErr: clienterrors.ErrUnsupportedDocument, wantStatus: http.StatusUnsupportedMediaType},
		"maps a document too large":     {serviceErr: clienterrors.ErrDocumentTooLarge, wantStatus: http.StatusRequestEntityTooLarge},
		"maps bad input":                {serviceErr: clienterrors.ErrBadInput, wantStatus: http.StatusBadRequest},
		"maps unexpected service error": {serviceErr: errors.New("S3 unavailable"), wantStatus: http.StatusInternalServerError},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			svc := servicemock.NewMockClientService(t)
			svc.On("UploadDocument", mock.Anything, "client-123", "agent-1", syntheticPNG).Return(stored, test.serviceErr).Once()
			res := httptest.NewRecorder()

			New(svc).UploadClientDocument(res, uploadRequest(t, "file", syntheticPNG))
			contract.Check(t, "clients-api.yaml", http.MethodPost, "/api/clients/{clientId}/documents", res)

			assert.Equal(t, test.wantStatus, res.Code)
			if test.wantStatus == http.StatusCreated {
				assert.Equal(t, "/api/clients/client-123/documents/"+testDocumentID, res.Header().Get("Location"))
				var got models.ClientDocument
				require.NoError(t, json.Unmarshal(res.Body.Bytes(), &got))
				assert.Equal(t, testDocumentID, got.DocumentID)
				assert.NotContains(t, res.Body.String(), "Alice Tan", "the file name is not kept")
			}
		})
	}
}

func TestUploadClientDocumentRefusesMalformedUploads(t *testing.T) {
	tests := map[string]struct {
		request    func(t *testing.T) *http.Request
		wantStatus int
	}{
		"no file field": {
			request:    func(t *testing.T) *http.Request { return uploadRequest(t, "document", syntheticPNG) },
			wantStatus: http.StatusBadRequest,
		},
		"not multipart": {
			request: func(t *testing.T) *http.Request {
				return httptest.NewRequest(http.MethodPost, "/api/clients/documents?id=client-123", strings.NewReader(string(syntheticPNG)))
			},
			wantStatus: http.StatusBadRequest,
		},
		"a body far larger than 5 MB": {
			request: func(t *testing.T) *http.Request {
				return uploadRequest(t, "file", bytes.Repeat([]byte{0}, documents.MaxSize+256<<10))
			},
			wantStatus: http.StatusRequestEntityTooLarge,
		},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			svc := servicemock.NewMockClientService(t)
			res := httptest.NewRecorder()

			New(svc).UploadClientDocument(res, test.request(t))
			contract.Check(t, "clients-api.yaml", http.MethodPost, "/api/clients/{clientId}/documents", res)

			assert.Equal(t, test.wantStatus, res.Code)
			svc.AssertNotCalled(t, "UploadDocument", mock.Anything, mock.Anything, mock.Anything, mock.Anything)
		})
	}
}

func TestUploadClientDocumentPassesALargeFileWithinTheBodyLimitToTheService(t *testing.T) {
	// The body fits the multipart limit, so the service sees the first
	// MaxSize+1 bytes and refuses them as too large.
	content := bytes.Repeat([]byte{0}, documents.MaxSize+16<<10)
	svc := servicemock.NewMockClientService(t)
	svc.On("UploadDocument", mock.Anything, "client-123", "agent-1", mock.MatchedBy(func(got []byte) bool {
		return len(got) == documents.MaxSize+1
	})).Return(models.ClientDocument{}, clienterrors.ErrDocumentTooLarge).Once()
	res := httptest.NewRecorder()

	New(svc).UploadClientDocument(res, uploadRequest(t, "file", content))
	contract.Check(t, "clients-api.yaml", http.MethodPost, "/api/clients/{clientId}/documents", res)

	assert.Equal(t, http.StatusRequestEntityTooLarge, res.Code)
}

func TestListClientDocuments(t *testing.T) {
	svc := servicemock.NewMockClientService(t)
	svc.On("ListDocuments", mock.Anything, "client-123").Return([]models.ClientDocument{}, nil).Once()
	res := httptest.NewRecorder()

	New(svc).ListClientDocuments(res, httptest.NewRequest(http.MethodGet, "/api/clients/documents?id=client-123", nil))
	contract.Check(t, "clients-api.yaml", http.MethodGet, "/api/clients/{clientId}/documents", res)

	assert.Equal(t, http.StatusOK, res.Code)
	assert.JSONEq(t, `[]`, res.Body.String(), "an empty list, not null")
}

func TestGetClientDocument(t *testing.T) {
	document := models.ClientDocument{DocumentID: testDocumentID, ClientID: "client-123", ContentType: "image/png", SizeBytes: len(syntheticPNG)}
	tests := map[string]struct {
		serviceErr error
		wantStatus int
	}{
		"returns the content":           {wantStatus: http.StatusOK},
		"maps missing client":           {serviceErr: clienterrors.ErrClientNotFound, wantStatus: http.StatusNotFound},
		"maps missing document":         {serviceErr: clienterrors.ErrDocumentNotFound, wantStatus: http.StatusNotFound},
		"maps unexpected service error": {serviceErr: errors.New("S3 unavailable"), wantStatus: http.StatusInternalServerError},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			svc := servicemock.NewMockClientService(t)
			svc.On("GetDocument", mock.Anything, "client-123", testDocumentID).Return(document, syntheticPNG, test.serviceErr).Once()
			res := httptest.NewRecorder()

			New(svc).GetClientDocument(res, httptest.NewRequest(http.MethodGet, "/api/clients/documents?id=client-123&documentId="+testDocumentID, nil))
			contract.Check(t, "clients-api.yaml", http.MethodGet, "/api/clients/{clientId}/documents/{documentId}", res)

			assert.Equal(t, test.wantStatus, res.Code)
			if test.wantStatus == http.StatusOK {
				assert.Equal(t, syntheticPNG, res.Body.Bytes())
				assert.Equal(t, "image/png", res.Header().Get("Content-Type"))
				assert.Equal(t, "no-store", res.Header().Get("Cache-Control"))
				assert.Equal(t, "nosniff", res.Header().Get("X-Content-Type-Options"))
			}
		})
	}
}
