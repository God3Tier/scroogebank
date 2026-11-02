package client

import (
	clientErr "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
)

const defaultPageSize = 20

func (c clientController) ListClients(w http.ResponseWriter, r *http.Request, caller models.Caller) {
	page, err := intParam(r, "page", 0)
	if err != nil {
		http.Error(w, "bad input: page must be a whole number", http.StatusBadRequest)
		return
	}
	size, err := intParam(r, "size", defaultPageSize)
	if err != nil {
		http.Error(w, "bad input: size must be a whole number", http.StatusBadRequest)
		return
	}

	ret, err := c.svc.ListClients(r.Context(), caller, page, size)
	if err != nil {
		switch {
		case errors.Is(err, clientErr.ErrBadInput):
			http.Error(w, err.Error(), http.StatusBadRequest)
		case errors.Is(err, clientErr.ErrForbidden):
			http.Error(w, err.Error(), http.StatusForbidden)
		default:
			writeServerError(w, r, err)
		}
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(ret)
}

// intParam reads an optional whole-number query parameter.
func intParam(r *http.Request, name string, fallback int) (int, error) {
	value := r.URL.Query().Get(name)
	if value == "" {
		return fallback, nil
	}
	return strconv.Atoi(value)
}
