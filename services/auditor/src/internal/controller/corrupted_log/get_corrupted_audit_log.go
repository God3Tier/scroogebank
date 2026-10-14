package corrupted_log

import (
	"encoding/json"
	"log"
	"net/http"
)

func (c Controller) GetCorruptedAuditLog(w http.ResponseWriter, r *http.Request) {
	logs, err := c.svc.GetCorruptedAuditLog(r.Context())

	if err != nil {
		log.Printf("get corrupted audit logs: %v", err)
		http.Error(w, "unable to retrieve corrupted audit logs", http.StatusInternalServerError)
		return
	}

	response := make([]corruptedAuditLogResponse, 0, len(logs))
	for _, entry := range logs {
		response = append(response, corruptedAuditLogToResponse(entry))
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(response)
}
