package client

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log"

	"client-profile-management-service/src/internal/documents"
	clienterrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	orm "client-profile-management-service/src/internal/repository/orm"

	"github.com/aarondl/sqlboiler/v4/boil"
	"github.com/aarondl/sqlboiler/v4/queries/qm"
)

// documentField binds a sealed document to its document ID, as a column name
// binds a field (ADR-0041 decision 3).
func documentField(documentID string) string {
	return "document/" + documentID
}

// CreateDocument seals the document with the client's data key, stores it and
// then records it. The object is stored first, so a row never points at a
// missing object; if the insert fails, the object is left unreferenced, still
// sealed with the client's key.
func (r clientRepository) CreateDocument(ctx context.Context, document models.ClientDocument, content []byte) error {
	client, err := r.findClient(ctx, document.ClientID)
	if err != nil {
		return err
	}
	key, err := r.protector.Key(ctx, client.ClientID)
	if err != nil {
		return err
	}
	sealed, err := key.SealBytes(documentField(document.DocumentID), content)
	if err != nil {
		return err
	}
	if err := r.documents.Put(ctx, documents.ObjectKey(client.ClientID, document.DocumentID), sealed); err != nil {
		return err
	}
	row := &orm.ClientDocument{
		DocumentID:        document.DocumentID,
		ClientID:          client.ClientID,
		ContentType:       document.ContentType,
		SizeBytes:         document.SizeBytes,
		UploadedByAgentID: document.UploadedByAgentID,
		UploadedAt:        document.UploadedAt,
	}
	if err := row.Insert(ctx, r.db, boil.Infer()); err != nil {
		log.Printf("document %s stored for client %s but not recorded: %v", document.DocumentID, client.ClientID, err)
		return err
	}
	return nil
}

// ListDocuments returns the client's documents, newest first, without their
// content.
func (r clientRepository) ListDocuments(ctx context.Context, clientID string) ([]models.ClientDocument, error) {
	client, err := r.findClient(ctx, clientID)
	if err != nil {
		return nil, err
	}
	rows, err := orm.ClientDocuments(
		orm.ClientDocumentWhere.ClientID.EQ(client.ClientID),
		qm.OrderBy(orm.ClientDocumentColumns.UploadedAt+" DESC, "+orm.ClientDocumentColumns.DocumentID),
	).All(ctx, r.db)
	if err != nil {
		return nil, err
	}
	list := make([]models.ClientDocument, 0, len(rows))
	for _, row := range rows {
		list = append(list, documentFrom(row))
	}
	return list, nil
}

// GetDocument returns one of the client's documents and its opened content.
func (r clientRepository) GetDocument(ctx context.Context, clientID, documentID string) (models.ClientDocument, []byte, error) {
	client, err := r.findClient(ctx, clientID)
	if err != nil {
		return models.ClientDocument{}, nil, err
	}
	row, err := orm.ClientDocuments(
		orm.ClientDocumentWhere.ClientID.EQ(client.ClientID),
		orm.ClientDocumentWhere.DocumentID.EQ(documentID),
	).One(ctx, r.db)
	if errors.Is(err, sql.ErrNoRows) {
		return models.ClientDocument{}, nil, clienterrors.ErrDocumentNotFound
	}
	if err != nil {
		return models.ClientDocument{}, nil, err
	}
	sealed, err := r.documents.Get(ctx, documents.ObjectKey(client.ClientID, row.DocumentID))
	if err != nil {
		return models.ClientDocument{}, nil, err
	}
	key, err := r.protector.Key(ctx, client.ClientID)
	if err != nil {
		return models.ClientDocument{}, nil, err
	}
	content, err := key.OpenBytes(documentField(row.DocumentID), sealed)
	if err != nil {
		return models.ClientDocument{}, nil, err
	}
	return documentFrom(row), content, nil
}

func documentFrom(row *orm.ClientDocument) models.ClientDocument {
	return models.ClientDocument{
		DocumentID:           row.DocumentID,
		ClientID:             row.ClientID,
		ContentType:          row.ContentType,
		SizeBytes:            row.SizeBytes,
		UploadedByAgentID:    row.UploadedByAgentID,
		UploadedAt:           row.UploadedAt,
		VerificationEvidence: row.VerificationEvidence,
	}
}

// countClientDocuments counts how many of documentIDs (already distinct) are
// documents of the client.
func countClientDocuments(ctx context.Context, exec boil.ContextExecutor, clientID string, documentIDs []string) (int64, error) {
	count, err := orm.ClientDocuments(
		orm.ClientDocumentWhere.ClientID.EQ(clientID),
		orm.ClientDocumentWhere.DocumentID.IN(documentIDs),
	).Count(ctx, exec)
	if err != nil {
		return 0, fmt.Errorf("count the client's documents: %w", err)
	}
	return count, nil
}
