// Package aggregate serves GET /aggregates/clients/*, the client figures the
// business dashboard asks for with the caller's own token. The routes sit
// outside /api, so API Gateway never routes them, and they are not audited
// here: the dashboard audits each view (ADR-0039).
package aggregate

import (
	"encoding/json"
	"errors"
	"log"
	"net/http"

	clienterrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	service "client-profile-management-service/src/internal/service/aggregate"
)

type Controller interface {
	ClientStats(w http.ResponseWriter, r *http.Request, caller models.Caller)
	ActionItems(w http.ResponseWriter, r *http.Request, caller models.Caller)
	TeamComparison(w http.ResponseWriter, r *http.Request, caller models.Caller)
}

type controller struct {
	svc service.Service
}

func New(svc service.Service) Controller {
	return controller{svc: svc}
}

// ClientStats serves GET /aggregates/clients/stats?from=&to=&groupBy=.
func (c controller) ClientStats(w http.ResponseWriter, r *http.Request, caller models.Caller) {
	query := r.URL.Query()
	stats, err := c.svc.ClientStats(r.Context(), caller, query.Get("from"), query.Get("to"), query.Get("groupBy"))
	respond(w, stats, err)
}

// ActionItems serves GET /aggregates/clients/action-items?staleDays=.
func (c controller) ActionItems(w http.ResponseWriter, r *http.Request, caller models.Caller) {
	items, err := c.svc.ActionItems(r.Context(), caller, r.URL.Query().Get("staleDays"))
	respond(w, items, err)
}

// TeamComparison serves GET /aggregates/clients/team-comparison?days=.
func (c controller) TeamComparison(w http.ResponseWriter, r *http.Request, caller models.Caller) {
	comparison, err := c.svc.TeamComparison(r.Context(), caller, r.URL.Query().Get("days"))
	respond(w, comparison, err)
}

func respond(w http.ResponseWriter, body any, err error) {
	w.Header().Set("Content-Type", "application/json")
	if err != nil {
		status, message := http.StatusInternalServerError, "unable to compute client figures"
		switch {
		case errors.Is(err, clienterrors.ErrBadInput):
			status, message = http.StatusBadRequest, err.Error()
		case errors.Is(err, clienterrors.ErrForbidden):
			status, message = http.StatusForbidden, err.Error()
		default:
			log.Printf("client aggregates failed: %v", err)
		}
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(map[string]string{"error": message})
		return
	}
	_ = json.NewEncoder(w).Encode(body)
}
