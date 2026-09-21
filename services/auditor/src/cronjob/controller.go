package cronjob

import (
	"auditor/src/internal/repository/audit"
	"auditor/src/internal/service"
	"context"
)

type Controller interface {
	VerifyHistoricalChain(ctx context.Context) 
	ReadOffset(ctx context.Context, checked *Checked) 
}

type impl struct {
	svc  service.Audit
	repo audit.Repository
}

func New(svc service.Audit, repo audit.Repository) Controller {
	return impl{
		svc,
		repo,
	}
}
