package account

import (
	"errors"
	"log"
	"net/http"
	"uuid"

	accounts "account-service/src/internal/errors"
)

func (c *accountController) DeleteAccount(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("id")
	if id == "" {
		http.Error(w, "id is required", http.StatusBadRequest)
		return
	}
	if _, err := uuid.Parse(id); err != nil {
		http.Error(w, "id must be a UUID", http.StatusBadRequest)
		return
	}

	err := c.svc.DeleteAccount(r.Context(), id)
	if err != nil {
		switch {
		case errors.Is(err, accounts.ErrBadInput):
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		case errors.Is(err, accounts.ErrNotFound):
			http.Error(w, err.Error(), http.StatusNotFound)
			return
		default:
			// The error's text can name tables and SQL, so it is logged, not sent.
			log.Printf("delete account %s: %v", id, err)
			http.Error(w, "unable to delete account", http.StatusInternalServerError)
			return
		}
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	w.Write([]byte(`{"status": "Account deleted successfully"}`))
}
