// Package aggregate serves GET /aggregates/accounts/*, the account figures the
// business dashboard and user-service ask for with the caller's own token.
// The routes sit outside /api, so API Gateway never routes them, and they are
// not audited here: the dashboard audits each view (ADR-0039).
package aggregate

import (
	"encoding/json"
	"errors"
	"log"
	"net/http"

	accounterrors "account-service/src/internal/errors"
	"account-service/src/internal/models"
	service "account-service/src/internal/service/aggregate"
)

type Controller interface {
	AccountStats(w http.ResponseWriter, r *http.Request, caller models.Caller)
	OwnedAccountIDs(w http.ResponseWriter, r *http.Request, caller models.Caller)
}

type controller struct {
	svc service.Service
}

func New(svc service.Service) Controller {
	return controller{svc: svc}
}

// AccountStats serves GET /aggregates/accounts/stats?from=&to=&groupBy=.
func (c controller) AccountStats(w http.ResponseWriter, r *http.Request, caller models.Caller) {
	query := r.URL.Query()
	stats, err := c.svc.AccountStats(r.Context(), caller, query.Get("from"), query.Get("to"), query.Get("groupBy"))
	respond(w, stats, err)
}

// OwnedAccountIDs serves GET /aggregates/accounts/ids.
func (c controller) OwnedAccountIDs(w http.ResponseWriter, r *http.Request, caller models.Caller) {
	ids, err := c.svc.OwnedAccountIDs(r.Context(), caller)
	respond(w, ids, err)
}

func respond(w http.ResponseWriter, body any, err error) {
	w.Header().Set("Content-Type", "application/json")
	if err != nil {
		status, message := http.StatusInternalServerError, "unable to compute account figures"
		switch {
		case errors.Is(err, accounterrors.ErrBadInput):
			status, message = http.StatusBadRequest, err.Error()
		case errors.Is(err, accounterrors.ErrForbidden):
			status, message = http.StatusForbidden, err.Error()
		case errors.Is(err, accounterrors.ErrMultipleCurrencies):
			status, message = http.StatusConflict, err.Error()
		default:
			log.Printf("account aggregates failed: %v", err)
		}
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(map[string]string{"error": message})
		return
	}
	_ = json.NewEncoder(w).Encode(body)
}
