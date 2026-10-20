// Package audit lets a handler tell the audit middleware what it changed, so
// the event can record the value before and after (the brief's audit
// attributes: attribute name, before value, after value). The middleware puts
// an empty Change in the request's context before the handler runs and reads
// it afterwards; a handler that changes nothing leaves it empty.
package audit

import "context"

type contextKey struct{}

// Change is one attribute's value before and after a request.
type Change struct {
	Attribute string
	Before    string
	After     string
}

// WithChange returns a context carrying an empty Change, and the Change for
// the caller to read once the handler has run.
func WithChange(ctx context.Context) (context.Context, *Change) {
	change := &Change{}
	return context.WithValue(ctx, contextKey{}, change), change
}

// Record notes what the request changed. It does nothing when the context
// has no Change, as in handler tests run without the middleware.
func Record(ctx context.Context, attribute, before, after string) {
	if change, ok := ctx.Value(contextKey{}).(*Change); ok {
		*change = Change{Attribute: attribute, Before: before, After: after}
	}
}
