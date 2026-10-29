package account

import (
	accounterrors "account-service/src/internal/errors"
	"encoding/json"
	"errors"
	"net/http"
)

type setOwnerRequest struct {
	ClientIDs    []string `json:"client_ids"`
	OwnerAgentID string   `json:"owner_agent_id"`
}

// SetOwner serves PUT /ownership/accounts. Only client-profile-management-
// service calls it, with an admin's token, while it reassigns clients
// (ADR-0060). It is not audited here: client-service audits each client moved.
func (c *accountController) SetOwner(w http.ResponseWriter, r *http.Request) {
	var request setOwnerRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 128<<10)).Decode(&request); err != nil {
		writeAccountError(w, http.StatusBadRequest, "body must be {\"client_ids\": [...], \"owner_agent_id\": \"...\"}")
		return
	}

	updated, err := c.svc.SetOwner(r.Context(), request.ClientIDs, request.OwnerAgentID)
	if err != nil {
		if errors.Is(err, accounterrors.ErrBadInput) {
			writeAccountError(w, http.StatusBadRequest, err.Error())
			return
		}
		writeAccountError(w, http.StatusInternalServerError, "unable to change the accounts' owner")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]int64{"accounts": updated})
}
