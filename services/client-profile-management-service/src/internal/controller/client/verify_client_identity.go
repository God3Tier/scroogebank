package client

import (
	clientErr "client-profile-management-service/src/internal/errors"
	"errors"
	"net/http"
)

func (c clientController) VerifyClientIdentity(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	idStr := r.URL.Query().Get("id")
	err := c.svc.VerifyClientIdentity(ctx, idStr)
	if err != nil {
		switch {
		case errors.Is(err, clientErr.ErrClientNotFound):
			http.Error(w, err.Error(), http.StatusNotFound)
			return
		default:
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
	}

	w.WriteHeader(http.StatusOK)
	w.Write([]byte(`{"status": "Client identity verified successfully"}`))
}
