package cronjob

import (
	"auditor/src/internal/repository/audit"
	auditSvc "auditor/src/internal/service/audit"
	"context"
)

type Controller interface {
	VerifyHistoricalChain(ctx context.Context)
	ReadOffset(ctx context.Context, checked *Checked)
}

type impl struct {
	auditSvc     auditSvc.Service
	repo         audit.Repository
}

func New(auditSvc auditSvc.Service, repo audit.Repository) Controller {
	return impl{
		auditSvc,
		repo,
	}
}
