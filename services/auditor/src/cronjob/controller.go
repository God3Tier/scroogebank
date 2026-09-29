package cronjob

import (
	"auditor/src/internal/repository/audit"
	auditSvc "auditor/src/internal/service/audit"
	corruptedSvc "auditor/src/internal/service/corrupted_logs"
	"context"
)

type Controller interface {
	VerifyHistoricalChain(ctx context.Context)
	ReadOffset(ctx context.Context, checked *Checked)
}

type impl struct {
	auditSvc     auditSvc.Service
	corruptedSvc corruptedSvc.Service
	repo         audit.Repository
}

func New(auditSvc auditSvc.Service, corruptedSvc corruptedSvc.Service, repo audit.Repository) Controller {
	return impl{
		auditSvc,
		corruptedSvc, 
		repo,
	}
}
