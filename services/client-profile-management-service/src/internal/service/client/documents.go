package client

import (
	"client-profile-management-service/src/internal/documents"
	clientErrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	"context"
	"fmt"
	"net/http"
	"strings"
	"time"
	"uuid"
)

// allowedDocumentTypes are the types an identity document may have, as
// http.DetectContentType names them from the first bytes (ADR-0041 decision
// 2). The file name and the type the browser states are never used.
var allowedDocumentTypes = map[string]bool{
	"image/jpeg":      true,
	"image/png":       true,
	"application/pdf": true,
}

func (s clientService) UploadDocument(ctx context.Context, clientID, agentID string, content []byte) (models.ClientDocument, error) {
	if strings.TrimSpace(clientID) == "" {
		return models.ClientDocument{}, fmt.Errorf("%w: client_id is required; %w", clientErrors.ErrBadInput, clientErrors.ErrInvalidClientID)
	}
	if strings.TrimSpace(agentID) == "" {
		return models.ClientDocument{}, fmt.Errorf("%w: the uploading agent is unknown", clientErrors.ErrBadInput)
	}
	if len(content) == 0 {
		return models.ClientDocument{}, fmt.Errorf("%w: the file is empty", clientErrors.ErrBadInput)
	}
	if len(content) > documents.MaxSize {
		return models.ClientDocument{}, clientErrors.ErrDocumentTooLarge
	}
	contentType := http.DetectContentType(content)
	if !allowedDocumentTypes[contentType] {
		return models.ClientDocument{}, clientErrors.ErrUnsupportedDocument
	}

	document := models.ClientDocument{
		DocumentID:        uuid.New().String(),
		ClientID:          clientID,
		ContentType:       contentType,
		SizeBytes:         len(content),
		UploadedByAgentID: agentID,
		UploadedAt:        time.Now().UTC(),
	}
	if err := s.repo.CreateDocument(ctx, document, content); err != nil {
		return models.ClientDocument{}, err
	}
	return document, nil
}

func (s clientService) ListDocuments(ctx context.Context, clientID string) ([]models.ClientDocument, error) {
	if strings.TrimSpace(clientID) == "" {
		return nil, fmt.Errorf("%w: client_id is required; %w", clientErrors.ErrBadInput, clientErrors.ErrInvalidClientID)
	}
	return s.repo.ListDocuments(ctx, clientID)
}

// GetDocument treats a malformed document ID as a missing document.
func (s clientService) GetDocument(ctx context.Context, clientID, documentID string) (models.ClientDocument, []byte, error) {
	if strings.TrimSpace(clientID) == "" {
		return models.ClientDocument{}, nil, fmt.Errorf("%w: client_id is required; %w", clientErrors.ErrBadInput, clientErrors.ErrInvalidClientID)
	}
	parsed, err := uuid.Parse(strings.TrimSpace(documentID))
	if err != nil {
		return models.ClientDocument{}, nil, clientErrors.ErrDocumentNotFound
	}
	return s.repo.GetDocument(ctx, clientID, parsed.String())
}
