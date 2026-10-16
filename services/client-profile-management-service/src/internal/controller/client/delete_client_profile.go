package client

import (
	client "client-profile-management-service/src/internal/errors"
	"errors"
	"net/http"
)

func (c clientController) DeleteClientProfile(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	idStr := r.URL.Query().Get("id")
	// The caller's own token goes to account-service, so its answer is scoped
	// to what the caller may see.
	err := c.svc.DeleteClientProfile(ctx, idStr, r.Header.Get("Authorization"))
	if err != nil {
		switch {
		case errors.Is(err, client.ErrClientNotFound):
			http.Error(w, err.Error(), http.StatusNotFound)
			return
		case errors.Is(err, client.ErrClientHasAccounts):
			http.Error(w, "client still has accounts; delete them first", http.StatusConflict)
			return
		case errors.Is(err, client.ErrAccountServiceUnavailable):
			http.Error(w, "account service unavailable", http.StatusServiceUnavailable)
			return
		default:
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
	}

	w.WriteHeader(http.StatusOK)
	w.Write([]byte(`{"status": "Client profile deleted successfully"}`))
}
