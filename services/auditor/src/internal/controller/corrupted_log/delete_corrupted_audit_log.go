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
		http.Error(w, "id is required", http.StatusBadRequest)
		return
	}
	if _, err := uuid.Parse(id); err != nil {
		http.Error(w, "id must be a valid UUID", http.StatusBadRequest)
		return
	}

	err := c.svc.DeleteCorruptedAuditLog(r.Context(), id)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			http.Error(w, "corrupted audit log not found", http.StatusNotFound)
			return
		}
		log.Printf("delete corrupted audit log: %v", err)
		http.Error(w, "unable to delete corrupted audit log", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]string{"status": "Corrupted audit log deleted successfully"})
}
