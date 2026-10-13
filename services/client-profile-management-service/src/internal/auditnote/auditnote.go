// Package auditnote lets a handler add document IDs to the audit event the
// audit middleware saves for its request, or note that a verification named
// no document (ADR-0041 decision 6). Only UUIDs are kept, so request data can
// never put anything else, such as a name, into the audit chain.
package auditnote

import (
	"context"
	"strings"
	"sync"
	"uuid"
)

type contextKey struct{}

// Notes collects the IDs for one request's audit event.
type Notes struct {
	mu              sync.Mutex
	documentIDs     []string
	withoutDocument bool
}

// WithNotes returns a context that carries new, empty notes.
func WithNotes(ctx context.Context) (context.Context, *Notes) {
	notes := &Notes{}
	return context.WithValue(ctx, contextKey{}, notes), notes
}

// AddDocumentIDs records document IDs for the request's audit event. It does
// nothing when the context carries no notes, and skips anything that is not a
// UUID.
func AddDocumentIDs(ctx context.Context, ids ...string) {
	notes, ok := ctx.Value(contextKey{}).(*Notes)
	if !ok {
		return
	}
	notes.add(ids...)
}

func (n *Notes) add(ids ...string) {
	n.mu.Lock()
	defer n.mu.Unlock()
	for _, raw := range ids {
		parsed, err := uuid.Parse(strings.TrimSpace(raw))
		if err != nil {
			continue
		}
		id := parsed.String()
		duplicate := false
		for _, existing := range n.documentIDs {
			if existing == id {
				duplicate = true
				break
			}
		}
		if !duplicate {
			n.documentIDs = append(n.documentIDs, id)
		}
	}
}

// DocumentIDs returns the recorded IDs, in the order first recorded.
func (n *Notes) DocumentIDs() []string {
	n.mu.Lock()
	defer n.mu.Unlock()
	return append([]string(nil), n.documentIDs...)
}

// MarkWithoutDocument notes that a verification named no document, so the
// audit event says so. It does nothing when the context carries no notes.
func MarkWithoutDocument(ctx context.Context) {
	notes, ok := ctx.Value(contextKey{}).(*Notes)
	if !ok {
		return
	}
	notes.mu.Lock()
	defer notes.mu.Unlock()
	notes.withoutDocument = true
}

// WithoutDocument reports whether MarkWithoutDocument was called.
func (n *Notes) WithoutDocument() bool {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.withoutDocument
}
