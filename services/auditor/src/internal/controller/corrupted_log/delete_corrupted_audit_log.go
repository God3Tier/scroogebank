package corrupted_log

import (
	"database/sql"
	"encoding/json"
	"errors"
	"log"
	"net/http"

	"github.com/google/uuid"
)

func (c Controller) DeleteCorruptedAuditLog(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("id")
	if id == "" {
		writeError(w, http.StatusBadRequest, "id is required")
		return
	}
	if _, err := uuid.Parse(id); err != nil {
		writeError(w, http.StatusBadRequest, "id must be a valid UUID")
		return
	}

	err := c.svc.DeleteCorruptedAuditLog(r.Context(), id)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			writeError(w, http.StatusNotFound, "corrupted audit log not found")
			return
		}
		log.Printf("delete corrupted audit log: %v", err)
		writeError(w, http.StatusInternalServerError, "unable to delete corrupted audit log")
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(statusResponse{Status: "Corrupted audit log deleted successfully"})
}
