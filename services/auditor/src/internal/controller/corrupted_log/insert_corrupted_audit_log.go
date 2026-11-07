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
		writeError(w, http.StatusBadRequest, "bad input")
		return
	}
	if _, err := uuid.Parse(request.ID); err != nil {
		writeError(w, http.StatusBadRequest, "id must be a valid UUID")
		return
	}

	if err := c.svc.InsertCorruptedAuditLog(r.Context(), request.model()); err != nil {
		log.Printf("insert corrupted audit log: %v", err)
		writeError(w, http.StatusInternalServerError, "unable to save corrupted audit log")
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(statusResponse{Status: "Corrupted audit log created successfully"})
}
