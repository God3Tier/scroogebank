package audit

import (
	auditv1 "auditor/src/gen/audit/v1"
	auditSvc "auditor/src/internal/service/audit"
)

// Controller serves the auditor's gRPC read API, proto/audit/v1 (ADR-0021).
type Controller struct {
	auditv1.UnimplementedAuditServiceServer
	svc auditSvc.Service
}

func New(svc auditSvc.Service) Controller {
	return Controller{
		svc: svc,
	}
}
