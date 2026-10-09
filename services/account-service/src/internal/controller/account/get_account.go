package account

import (
	"encoding/json"
	"errors"
	"net/http"

	clienterrors "account-service/src/internal/errors"
)

func (c *accountController) GetAccount(w http.ResponseWriter, r *http.Request) {
	accountID := r.URL.Query().Get("id")
	if accountID == "" {
		writeGetAccountError(w, http.StatusBadRequest, "id is required")
		return
	}

	account, err := c.svc.GetAccount(r.Context(), accountID)
	if err != nil {
		if errors.Is(err, clienterrors.ErrNotFound) {
			writeGetAccountError(w, http.StatusNotFound, "account not found")
			return
		}
		writeGetAccountError(w, http.StatusInternalServerError, "unable to retrieve account")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(account); err != nil {
		writeGetAccountError(w, http.StatusInternalServerError, "unable to encode account")
	}
}

func writeGetAccountError(w http.ResponseWriter, status int, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": message})
}
