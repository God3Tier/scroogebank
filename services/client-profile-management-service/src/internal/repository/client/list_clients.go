package client

import (
	"client-profile-management-service/src/internal/encryption"
	"client-profile-management-service/src/internal/models"
	orm "client-profile-management-service/src/internal/repository/orm"
	"context"
	"errors"
	"fmt"
	"log"
	"sync"

	"github.com/aarondl/sqlboiler/v4/queries/qm"
)

// listDecryptWorkers bounds how many of a page's rows are decrypted at once.
// Each row has its own data key (ADR-0030), and a key not in the cache costs a
// DynamoDB read and a KMS call, so a page is decrypted in parallel rather than
// one row after another (ADR-0072).
const listDecryptWorkers = 8

// ListClients returns one page of clients that are not deleted, newest first,
// and how many there are in all. An empty ownerID lists every client; any
// other value lists only the clients that agent created (ADR-0043).
func (r clientRepository) ListClients(ctx context.Context, ownerID string, page, size int) ([]models.Client, int64, error) {
	var scope []qm.QueryMod
	if ownerID != "" {
		scope = append(scope, qm.Where(orm.ClientColumns.AssignedAgentID+" = ?", ownerID))
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

	profiles := make([]models.Client, len(rows))
	errs := make([]error, len(rows))
	slots := make(chan struct{}, listDecryptWorkers)
	var wg sync.WaitGroup
	for i, row := range rows {
		wg.Add(1)
		slots <- struct{}{}
		go func() {
			defer wg.Done()
			defer func() { <-slots }()
			profiles[i], errs[i] = r.profileFrom(ctx, row)
		}()
	}
	wg.Wait()

	clients := make([]models.Client, 0, len(rows))
	for i, row := range rows {
		if errors.Is(errs[i], encryption.ErrKeyNotFound) {
			// The client was erased by deleting its data key (ADR-0030); one
			// such row must not hide everyone else's. It still counts in total.
			log.Printf("list clients: skipped client %s without a data key", row.ClientID)
			continue
		}
		if errs[i] != nil {
			return nil, 0, errs[i]
		}
		clients = append(clients, profiles[i])
	}
	return clients, total, nil
}
