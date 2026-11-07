package security

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

// fakeVerifier accepts the tokens it knows.
type fakeVerifier map[string]Principal

func (f fakeVerifier) Verify(token string) (Principal, error) {
	principal, ok := f[token]
	if !ok {
		return Principal{}, errors.New("invalid token")
	}
	return principal, nil
}

var verifier = fakeVerifier{
	"admin-token":    {Subject: "admin-sub", Role: "ADMIN"},
	"agent-token":    {Subject: "agent-sub", Role: "AGENT"},
	"no-group-token": {Subject: "someone-sub"},
}

func TestUnaryInterceptor(t *testing.T) {
	tests := map[string]struct {
		metadata   []string
		wantCode   codes.Code
		wantCaller Principal
	}{
		"admits an agent":                {metadata: []string{"authorization", "Bearer agent-token"}, wantCaller: verifier["agent-token"]},
		"admits an admin":                {metadata: []string{"authorization", "bearer admin-token"}, wantCaller: verifier["admin-token"]},
		"refuses a call with no token":   {wantCode: codes.Unauthenticated},
		"refuses a token without Bearer": {metadata: []string{"authorization", "agent-token"}, wantCode: codes.Unauthenticated},
		"refuses an invalid token":       {metadata: []string{"authorization", "Bearer forged"}, wantCode: codes.Unauthenticated},
		"refuses a user in no CRM group": {metadata: []string{"authorization", "Bearer no-group-token"}, wantCode: codes.PermissionDenied},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			ctx := context.Background()
			if test.metadata != nil {
				ctx = metadata.NewIncomingContext(ctx, metadata.Pairs(test.metadata...))
			}
			var got Principal
			handler := func(ctx context.Context, _ any) (any, error) {
				caller, ok := CallerFrom(ctx)
				require.True(t, ok)
				got = caller
				return "ok", nil
			}

			_, err := UnaryInterceptor(verifier)(ctx, nil, &grpc.UnaryServerInfo{}, handler)

			assert.Equal(t, test.wantCode, status.Code(err))
			assert.Equal(t, test.wantCaller, got)
		})
	}
}

func TestAdminOnly(t *testing.T) {
	tests := map[string]struct {
		authorization string
		wantStatus    int
	}{
		"admits an admin":              {authorization: "Bearer admin-token", wantStatus: http.StatusOK},
		"refuses an agent":             {authorization: "Bearer agent-token", wantStatus: http.StatusForbidden},
		"refuses a request with none":  {wantStatus: http.StatusUnauthorized},
		"refuses an invalid token":     {authorization: "Bearer forged", wantStatus: http.StatusUnauthorized},
		"refuses a token with no type": {authorization: "admin-token", wantStatus: http.StatusUnauthorized},
	}
	gin.SetMode(gin.TestMode)
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			router := gin.New()
			router.GET("/", AdminOnly(verifier), func(c *gin.Context) { c.Status(http.StatusOK) })
			request := httptest.NewRequest(http.MethodGet, "/", nil)
			if test.authorization != "" {
				request.Header.Set("Authorization", test.authorization)
			}
			response := httptest.NewRecorder()

			router.ServeHTTP(response, request)

			assert.Equal(t, test.wantStatus, response.Code)
		})
	}
}
