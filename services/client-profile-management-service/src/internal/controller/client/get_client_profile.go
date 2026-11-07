package client

import (
	clientErr "client-profile-management-service/src/internal/errors"
	"encoding/json"
	"errors"
	"net/http"
)

func (c clientController) GetClientProfile(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	idStr := r.URL.Query().Get("id")
	ret, err := c.svc.GetClientProfile(ctx, idStr)
	if err != nil {
		switch {
		case errors.Is(err, clientErr.ErrClientNotFound):
			http.Error(w, err.Error(), http.StatusNotFound)
			return
		default:
			writeServerError(w, r, err)
			return
		}
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(ret)
}
