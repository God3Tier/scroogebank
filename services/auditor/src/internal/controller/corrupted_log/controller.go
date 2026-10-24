package corrupted_log

import (
	corrupted_log "auditor/src/internal/service/corrupted_logs"
	"encoding/json"
	"net/http"
)

type Controller struct {
	svc corrupted_log.Service
}

func New(svc corrupted_log.Service) Controller {
	return Controller{
		svc: svc,
	}
}

// writeError answers an error as JSON, like the authentication middleware.
// user-service passes these replies on to the admin, and reads each as JSON.
func writeError(w http.ResponseWriter, status int, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": message})
}
