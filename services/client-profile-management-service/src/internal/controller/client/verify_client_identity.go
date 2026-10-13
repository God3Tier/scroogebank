package client

import (
	"client-profile-management-service/src/internal/auditnote"
	clientErr "client-profile-management-service/src/internal/errors"
	"encoding/json"
	"errors"
	"io"
	"net/http"
)

// maxVerifyBodySize is far more than a list of document IDs needs.
const maxVerifyBodySize = 64 << 10

// VerifyClientIdentityInput names the documents the agent checked against the
// client's details. The body is optional: without documents, the client is
// verified without one, and the audit event says so (ADR-0041 decision 5).
type VerifyClientIdentityInput struct {
	DocumentIDs []string `json:"document_ids"`
}

func (c clientController) VerifyClientIdentity(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	idStr := r.URL.Query().Get("id")
	var input VerifyClientIdentityInput
	err := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxVerifyBodySize)).Decode(&input)
	if err != nil && !errors.Is(err, io.EOF) {
		http.Error(w, "bad input: send no body, or the document_ids you checked", http.StatusBadRequest)
		return
	}
	if len(input.DocumentIDs) == 0 {
		auditnote.MarkWithoutDocument(ctx)
	}
	auditnote.AddDocumentIDs(ctx, input.DocumentIDs...)

	err = c.svc.VerifyClientIdentity(ctx, idStr, r.Header.Get("X-Agent-ID"), input.DocumentIDs)
	if err != nil {
		switch {
		case errors.Is(err, clientErr.ErrClientNotFound):
			http.Error(w, err.Error(), http.StatusNotFound)
		case errors.Is(err, clientErr.ErrAlreadyVerified):
			http.Error(w, err.Error(), http.StatusConflict)
		case errors.Is(err, clientErr.ErrDocumentNotFound):
			http.Error(w, "bad input: every document in document_ids must be one of this client's documents", http.StatusBadRequest)
		case errors.Is(err, clientErr.ErrBadInput):
			http.Error(w, err.Error(), http.StatusBadRequest)
		default:
			http.Error(w, err.Error(), http.StatusInternalServerError)
		}
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	w.Write([]byte(`{"status": "Client identity verified successfully"}`))
}
