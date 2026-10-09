package security

import (
	"context"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

// Verifier checks a Cognito access token and says who sent it.
type Verifier interface {
	Verify(token string) (Principal, error)
}

// Admin reports whether the caller is in the ADMIN group.
func (p Principal) Admin() bool {
	return p.Role == "ADMIN"
}

type callerKey struct{}

// WithCaller returns ctx carrying the verified caller.
func WithCaller(ctx context.Context, caller Principal) context.Context {
	return context.WithValue(ctx, callerKey{}, caller)
}

// CallerFrom returns the caller that UnaryInterceptor verified.
func CallerFrom(ctx context.Context) (Principal, bool) {
	caller, ok := ctx.Value(callerKey{}).(Principal)
	return caller, ok
}

// UnaryInterceptor admits a gRPC call only with a CRM user's access token in
// its authorization metadata, forwarded by the calling service (ADR-0021
// decision 6). Handlers find the verified caller with CallerFrom.
func UnaryInterceptor(verifier Verifier) grpc.UnaryServerInterceptor {
	return func(ctx context.Context, req any, _ *grpc.UnaryServerInfo, handler grpc.UnaryHandler) (any, error) {
		md, _ := metadata.FromIncomingContext(ctx)
		token, ok := bearerToken(md.Get("authorization"))
		if !ok {
			return nil, status.Error(codes.Unauthenticated, "bearer access token required")
		}
		caller, err := verifier.Verify(token)
		if err != nil {
			return nil, status.Error(codes.Unauthenticated, "invalid access token")
		}
		if caller.Role == "" {
			return nil, status.Error(codes.PermissionDenied, "ADMIN or AGENT role required")
		}
		return handler(WithCaller(ctx, caller), req)
	}
}

// AdminOnly admits an HTTP request only with an admin's access token. The
// corrupted-log endpoints use it, so they cannot be reached around
// user-service's admin check (ADR-0021 decision 4).
func AdminOnly(verifier Verifier) gin.HandlerFunc {
	return func(c *gin.Context) {
		token, ok := bearerToken(c.Request.Header.Values("Authorization"))
		if !ok {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Bearer access token required"})
			return
		}
		caller, err := verifier.Verify(token)
		if err != nil {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid access token"})
			return
		}
		if !caller.Admin() {
			c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "ADMIN role required"})
			return
		}
		c.Next()
	}
}

// bearerToken reads "Bearer <token>" from a single header or metadata value.
func bearerToken(values []string) (string, bool) {
	if len(values) != 1 {
		return "", false
	}
	parts := strings.Fields(values[0])
	if len(parts) != 2 || !strings.EqualFold(parts[0], "Bearer") {
		return "", false
	}
	return parts[1], true
}
