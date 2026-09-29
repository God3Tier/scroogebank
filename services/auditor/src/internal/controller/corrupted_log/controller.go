package corrupted_log

import corrupted_log "auditor/src/internal/service/corrupted_logs"

type Controller struct {
	svc corrupted_log.Service
}

func New(svc corrupted_log.Service) Controller {
	return Controller{
		svc: svc,
	}
}
