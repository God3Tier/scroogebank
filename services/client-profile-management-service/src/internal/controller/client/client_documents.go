package client

import (
	"client-profile-management-service/src/internal/auditnote"
	"client-profile-management-service/src/internal/documents"
	clientErr "client-profile-management-service/src/internal/errors"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
)

// maxUploadBodySize allows a 5 MB document plus the multipart framing around
// it. Anything larger is refused before it is read.
const maxUploadBodySize = documents.MaxSize + 64<<10

// UploadClientDocument stores one identity document sent as the multipart
// field "file" (ADR-0041 decision 2).
func (c clientController) UploadClientDocument(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	r.Body = http.MaxBytesReader(w, r.Body, maxUploadBodySize)
	file, _, err := r.FormFile("file")
	if r.MultipartForm != nil {
		defer r.MultipartForm.RemoveAll()
	}
	if err != nil {
		var tooLarge *http.MaxBytesError
		if errors.As(err, &tooLarge) {
			http.Error(w, clientErr.ErrDocumentTooLarge.Error(), http.StatusRequestEntityTooLarge)
			return
		}
		http.Error(w, "bad input: send the document as the multipart field \"file\"", http.StatusBadRequest)
		return
	}
	defer file.Close()
	content, err := io.ReadAll(io.LimitReader(file, documents.MaxSize+1))
	if err != nil {
		http.Error(w, "bad input: the file could not be read", http.StatusBadRequest)
		return
	}

	document, err := c.svc.UploadDocument(ctx, r.URL.Query().Get("id"), r.Header.Get("X-Agent-ID"), content)
	if err != nil {
		switch {
		case errors.Is(err, clientErr.ErrClientNotFound):
			http.Error(w, err.Error(), http.StatusNotFound)
		case errors.Is(err, clientErr.ErrDocumentTooLarge):
			http.Error(w, err.Error(), http.StatusRequestEntityTooLarge)
		case errors.Is(err, clientErr.ErrUnsupportedDocument):
			http.Error(w, err.Error(), http.StatusUnsupportedMediaType)
		case errors.Is(err, clientErr.ErrBadInput):
			http.Error(w, err.Error(), http.StatusBadRequest)
		default:
			writeServerError(w, r, err)
		}
		return
	}
	auditnote.AddDocumentIDs(ctx, document.DocumentID)

	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Location", "/api/clients/"+document.ClientID+"/documents/"+document.DocumentID)
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(document)
}

// ListClientDocuments lists the client's documents, newest first, without
// their content.
func (c clientController) ListClientDocuments(w http.ResponseWriter, r *http.Request) {
	list, err := c.svc.ListDocuments(r.Context(), r.URL.Query().Get("id"))
	if err != nil {
		switch {
		case errors.Is(err, clientErr.ErrClientNotFound):
			http.Error(w, err.Error(), http.StatusNotFound)
		case errors.Is(err, clientErr.ErrBadInput):
			http.Error(w, err.Error(), http.StatusBadRequest)
		default:
			writeServerError(w, r, err)
		}
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(list)
}

// GetClientDocument returns one document's content with its stored type. It
// is never cached, and nosniff stops a browser treating it as another type
// (ADR-0041 decision 4).
func (c clientController) GetClientDocument(w http.ResponseWriter, r *http.Request) {
	document, content, err := c.svc.GetDocument(r.Context(), r.URL.Query().Get("id"), r.URL.Query().Get("documentId"))
	if err != nil {
		switch {
		case errors.Is(err, clientErr.ErrClientNotFound), errors.Is(err, clientErr.ErrDocumentNotFound):
			http.Error(w, err.Error(), http.StatusNotFound)
		case errors.Is(err, clientErr.ErrBadInput):
			http.Error(w, err.Error(), http.StatusBadRequest)
		default:
			writeServerError(w, r, err)
		}
		return
	}

	w.Header().Set("Content-Type", document.ContentType)
	w.Header().Set("Content-Length", strconv.Itoa(len(content)))
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(content)
}
