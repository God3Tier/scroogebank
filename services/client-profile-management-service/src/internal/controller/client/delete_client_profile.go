package client

import (
	client "client-profile-management-service/src/internal/errors"
	"errors"
	"net/http"
)


func (c clientController) DeleteClientProfile(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	idStr := r.URL.Query().Get("id")
	err := c.svc.DeleteClientProfile(ctx, idStr)
	if err != nil {
		switch {
		case errors.Is(err, client.ErrClientNotFound):
			http.Error(w, err.Error(), http.StatusNotFound)
			return
		default:
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
	}

	w.WriteHeader(http.StatusOK)
	w.Write([]byte(`{"status": "Client profile deleted successfully"}`))
}
