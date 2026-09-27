package account

import (
	"errors"
	"net/http"

	accounts "client-profile-management-service/src/internal/errors"
)

func (c *accountController) DeleteAccount(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("id")
	if id == "" {
		http.Error(w, "id is required", http.StatusBadRequest)
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
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
	}

	w.WriteHeader(http.StatusOK)
	w.Write([]byte(`{"status": "Account deleted successfully"}`))
}
