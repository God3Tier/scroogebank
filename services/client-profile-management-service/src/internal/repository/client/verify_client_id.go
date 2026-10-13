package client

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"time"

	clienterrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	orm "client-profile-management-service/src/internal/repository/orm"

	"github.com/aarondl/null/v8"
	"github.com/aarondl/sqlboiler/v4/boil"
	"github.com/aarondl/sqlboiler/v4/queries/qm"
)

// VerifyClientIdentity records the agent's decision and the documents it was
// based on, in one transaction (ADR-0041 decision 5). documentIDs must be
// distinct, and may be empty: documents are optional, and a verification
// without one is recorded as such. The client row is locked first, so two
// verifications of the same client cannot both succeed.
func (r clientRepository) VerifyClientIdentity(ctx context.Context, clientID, agentID string, documentIDs []string) error {
	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()

	// The generated query skips deleted clients (ADR-0036).
	client, err := orm.Clients(orm.ClientWhere.ClientID.EQ(clientID), qm.For("UPDATE")).One(ctx, tx)
	if errors.Is(err, sql.ErrNoRows) {
		return clienterrors.ErrClientNotFound
	}
	if err != nil {
		return err
	}
	// Clients verified before ADR-0041 hold "verified".
	if strings.EqualFold(client.VerificationStatus, models.VerifiedStatus) {
		return clienterrors.ErrAlreadyVerified
	}
	withDocuments := len(documentIDs) > 0
	if withDocuments {
		count, err := countClientDocuments(ctx, tx, client.ClientID, documentIDs)
		if err != nil {
			return err
		}
		if count != int64(len(documentIDs)) {
			return clienterrors.ErrDocumentNotFound
		}
	}

	now := time.Now()
	client.VerificationStatus = models.VerifiedStatus
	client.VerifiedByAgentID = null.StringFrom(agentID)
	client.VerifiedAt = null.TimeFrom(now)
	client.VerifiedWithDocuments = null.BoolFrom(withDocuments)
	client.UpdatedAt = now
	if _, err := client.Update(ctx, tx, boil.Whitelist(
		orm.ClientColumns.VerificationStatus, orm.ClientColumns.VerifiedByAgentID,
		orm.ClientColumns.VerifiedAt, orm.ClientColumns.VerifiedWithDocuments,
		orm.ClientColumns.UpdatedAt,
	)); err != nil {
		return err
	}
	if withDocuments {
		if _, err := orm.ClientDocuments(
			orm.ClientDocumentWhere.ClientID.EQ(client.ClientID),
			orm.ClientDocumentWhere.DocumentID.IN(documentIDs),
		).UpdateAll(ctx, tx, orm.M{orm.ClientDocumentColumns.VerificationEvidence: true}); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// DeleteClient soft-deletes: it sets deleted_at and keeps the row (ADR-0036).
func (r clientRepository) DeleteClient(ctx context.Context, clientID string) error {
	client, err := r.findClient(ctx, clientID)
	if err != nil {
		return err
	}

	_, err = client.Delete(ctx, r.db, false) // false: soft delete
	return err
}
