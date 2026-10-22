package corrupted_log

import (
	"encoding/json"
	"log"
	"net/http"

	"github.com/google/uuid"
)

func (c Controller) InsertCorruptedAuditLog(w http.ResponseWriter, r *http.Request) {
	var request insertCorruptedAuditLogRequest
	if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
		http.Error(w, "bad input", http.StatusBadRequest)
		return
	}
	if _, err := uuid.Parse(request.ID); err != nil {
		http.Error(w, "id must be a valid UUID", http.StatusBadRequest)
		return
	}

	if err := c.svc.InsertCorruptedAuditLog(r.Context(), request.model()); err != nil {
		log.Printf("insert corrupted audit log: %v", err)
		http.Error(w, "unable to save corrupted audit log", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(statusResponse{Status: "Corrupted audit log created successfully"})
}
