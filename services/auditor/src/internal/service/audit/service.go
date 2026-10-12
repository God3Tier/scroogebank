package audit

import (
	"auditor/src/internal/models"
	"auditor/src/internal/repository/audit"
	"context"
)

// Service is the placeholder for auditor business logic.
type Service interface {
	// VerifyChain checks every entry of the audit chain, oldest first, and
	// returns how many entries it checked and the problems it found
	// (ADR-0022 decision 3). It only reads.
	VerifyChain(ctx context.Context) (int64, []models.ChainFinding, error)
	// ListActivities returns the activity feed's entries matching filter, newest first.
	ListActivities(ctx context.Context, filter models.ActivityFilter) ([]models.AuditLog, error)
}

// verifyPageSize is how many entries VerifyChain reads at a time.
const verifyPageSize = 1000

// TODO: Figure out specifically what is needed for this service
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
