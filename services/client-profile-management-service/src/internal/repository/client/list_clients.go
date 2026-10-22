package client

import (
	"client-profile-management-service/src/internal/encryption"
	"client-profile-management-service/src/internal/models"
	orm "client-profile-management-service/src/internal/repository/orm"
	"context"
	"errors"
	"fmt"
	"log"

	"github.com/aarondl/sqlboiler/v4/queries/qm"
)

// ListClients returns one page of clients that are not deleted, newest first,
// and how many there are in all. An empty ownerID lists every client; any
// other value lists only the clients that agent created (ADR-0043).
func (r clientRepository) ListClients(ctx context.Context, ownerID string, page, size int) ([]models.Client, int64, error) {
	var scope []qm.QueryMod
	if ownerID != "" {
		scope = append(scope, qm.Where(orm.ClientColumns.CreatedByAgentID+" = ?", ownerID))
	}

	total, err := orm.Clients(scope...).Count(ctx, r.db)
	if err != nil {
		return nil, 0, fmt.Errorf("count clients: %w", err)
	}

	rows, err := orm.Clients(append(scope,
		qm.OrderBy(orm.ClientColumns.CreatedAt+" DESC, "+orm.ClientColumns.ClientID),
		qm.Limit(size),
		qm.Offset(page*size),
	)...).All(ctx, r.db)
	if err != nil {
		return nil, 0, fmt.Errorf("list clients: %w", err)
	}

	clients := make([]models.Client, 0, len(rows))
	for _, row := range rows {
		profile, err := r.profileFrom(ctx, row)
		if errors.Is(err, encryption.ErrKeyNotFound) {
			// The client was erased by deleting its data key (ADR-0030); one
			// such row must not hide everyone else's. It still counts in total.
			log.Printf("list clients: skipped client %s without a data key", row.ClientID)
			continue
		}
		if err != nil {
			return nil, 0, err
		}
		clients = append(clients, profile)
	}
	return clients, total, nil
}
