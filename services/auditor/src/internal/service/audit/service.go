package audit

import (
	"context"

	"auditor/src/internal/models"
	"auditor/src/internal/repository/audit"
)

// Service provides audit-chain verification and activity-feed queries.
type Service interface {
	// VerifyChain checks every entry of the audit chain, oldest first, and the
	// entry anchor names (nil for none). It reports how many entries it
	// checked, the problems it found and the newest entry (ADR-0022
	// decision 3). It only reads.
	VerifyChain(ctx context.Context, anchor *models.ChainAnchor) (models.ChainReport, error)
	// ListActivities returns the activity feed's entries matching filter, newest first.
	ListActivities(ctx context.Context, filter models.ActivityFilter) ([]models.AuditLog, error)
}

// verifyPageSize is how many entries VerifyChain reads at a time.
const verifyPageSize = 1000

type impl struct {
	repo     audit.Repository
	pageSize int
}

func New(repo audit.Repository) Service {
	return impl{
		repo:     repo,
		pageSize: verifyPageSize,
	}
}
