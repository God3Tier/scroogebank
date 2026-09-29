package corrupted_log

import (
	"auditor/src/internal/models"
	"encoding/json"
	"log"
	"net/http"

	"github.com/google/uuid"
)

func (c Controller) InsertCorruptedAuditLog(w http.ResponseWriter, r *http.Request) {
	var raw models.CorruptedAuditLog
	if err := json.NewDecoder(r.Body).Decode(&raw); err != nil {
		http.Error(w, "bad input", http.StatusBadRequest)
		return
	}
	if _, err := uuid.Parse(raw.ID); err != nil {
		http.Error(w, "id must be a valid UUID", http.StatusBadRequest)
		return
	}

	if err := c.svc.InsertCorruptedAuditLog(r.Context(), raw); err != nil {
		log.Printf("insert corrupted audit log: %v", err)
		http.Error(w, "unable to save corrupted audit log", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(map[string]string{"status": "Corrupted audit log created successfully"})
}
