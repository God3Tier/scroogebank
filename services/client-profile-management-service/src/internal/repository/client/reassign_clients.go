package client

import (
	"context"
	"time"

	clienterrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	orm "client-profile-management-service/src/internal/repository/orm"

	"github.com/aarondl/sqlboiler/v4/queries/qm"
)

// ReassignClients gives clients to toAgentID in one transaction (ADR-0060):
// every client of fromAgentID when it is set, or else the clients named by
// clientIDs, which must all exist. Clients toAgentID already has are left out.
//
// The clients are locked first, and moveAccounts is called with the ones that
// move before they change, so their accounts follow them. If moveAccounts
// fails, nothing is committed. Deleted clients are never moved (ADR-0036).
func (r clientRepository) ReassignClients(ctx context.Context, fromAgentID string, clientIDs []string, toAgentID string,
	moveAccounts func(ctx context.Context, clientIDs []string) error) ([]models.Reassignment, error) {
	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback() }()

	selection := []qm.QueryMod{qm.For("UPDATE"), qm.OrderBy(orm.ClientColumns.ClientID)}
	if fromAgentID != "" {
		// One more than the limit shows whether there are too many.
		selection = append(selection, orm.ClientWhere.AssignedAgentID.EQ(fromAgentID), qm.Limit(models.MaxReassignedClients+1))
	} else {
		selection = append(selection, orm.ClientWhere.ClientID.IN(clientIDs))
	}
	// The generated query skips deleted clients.
	clients, err := orm.Clients(selection...).All(ctx, tx)
	if err != nil {
		return nil, err
	}
	if fromAgentID != "" && len(clients) > models.MaxReassignedClients {
		return nil, clienterrors.ErrTooManyClients
	}
	if fromAgentID == "" && len(clients) != len(clientIDs) {
		return nil, clienterrors.ErrClientNotFound
	}

	moved := make([]models.Reassignment, 0, len(clients))
	ids := make([]string, 0, len(clients))
	for _, client := range clients {
		if client.AssignedAgentID == toAgentID {
			continue
		}
		moved = append(moved, models.Reassignment{ClientID: client.ClientID, FromAgentID: client.AssignedAgentID, ToAgentID: toAgentID})
		ids = append(ids, client.ClientID)
	}
	if len(ids) == 0 {
		return moved, tx.Commit()
	}

	if err := moveAccounts(ctx, ids); err != nil {
		return nil, err
	}
	if _, err := orm.Clients(orm.ClientWhere.ClientID.IN(ids)).UpdateAll(ctx, tx, orm.M{
		orm.ClientColumns.AssignedAgentID: toAgentID,
		orm.ClientColumns.UpdatedAt:       time.Now(),
	}); err != nil {
		return nil, err
	}
	if err := tx.Commit(); err != nil {
		return nil, err
	}
	return moved, nil
}
