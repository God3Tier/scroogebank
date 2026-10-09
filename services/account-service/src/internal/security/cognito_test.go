package security

import (
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const (
	testIssuer        = "https://cognito-idp.ap-southeast-1.amazonaws.com/ap-southeast-1_test"
	testUserClient    = "crm-web-client"
	testServiceClient = "transaction-ingest-client"
	testScope         = "crm-accounts/lookup"
)

// The verifier's key cache is seeded with a test key, so no request reaches
// Cognito: the tokens below are signed exactly as Cognito would sign them.
func newTestVerifier(t *testing.T) (*CognitoVerifier, *rsa.PrivateKey) {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	require.NoError(t, err)
	return &CognitoVerifier{
		issuer:   testIssuer,
		clientID: testUserClient,
		client:   http.DefaultClient,
		keys:     map[string]*rsa.PublicKey{"test-key": &key.PublicKey},
		keysAt:   time.Now(),
	}, key
}

func signToken(t *testing.T, key *rsa.PrivateKey, claims map[string]any) string {
	t.Helper()
	encode := func(value any) string {
		raw, err := json.Marshal(value)
		require.NoError(t, err)
		return base64.RawURLEncoding.EncodeToString(raw)
	}
	unsigned := encode(map[string]string{"alg": "RS256", "kid": "test-key", "typ": "JWT"}) + "." + encode(claims)
	digest := sha256.Sum256([]byte(unsigned))
	signature, err := rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, digest[:])
	require.NoError(t, err)
	return unsigned + "." + base64.RawURLEncoding.EncodeToString(signature)
}

func userClaims() map[string]any {
	now := time.Now().Unix()
	return map[string]any{
		"iss": testIssuer, "sub": "agent-sub", "client_id": testUserClient, "token_use": "access",
		"cognito:groups": []string{"AGENT"}, "scope": "aws.cognito.signin.user.admin", "iat": now, "exp": now + 3600,
	}
}

// A client-credentials token: Cognito sets sub to the app client ID and adds
// the granted scopes, with no groups.
func serviceClaims() map[string]any {
	now := time.Now().Unix()
	return map[string]any{
		"iss": testIssuer, "sub": testServiceClient, "client_id": testServiceClient, "token_use": "access",
		"scope": testScope, "iat": now, "exp": now + 3600,
	}
}

func serve(t *testing.T, middleware gin.HandlerFunc, token string) int {
	t.Helper()
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.GET("/", middleware, func(c *gin.Context) { c.Status(http.StatusOK) })
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	res := httptest.NewRecorder()
	router.ServeHTTP(res, req)
	return res.Code
}

func TestServiceMiddleware(t *testing.T) {
	verifier, key := newTestVerifier(t)
	middleware := verifier.ServiceMiddleware([]string{testServiceClient}, testScope)
	withClaim := func(name string, value any) map[string]any {
		claims := serviceClaims()
		claims[name] = value
		return claims
	}

	tests := map[string]struct {
		token      string
		wantStatus int
	}{
		"admits the import's service token":    {token: signToken(t, key, serviceClaims()), wantStatus: http.StatusOK},
		"admits the scope among others":        {token: signToken(t, key, withClaim("scope", "crm-accounts/other "+testScope)), wantStatus: http.StatusOK},
		"refuses another app client":           {token: signToken(t, key, withClaim("client_id", "someone-else")), wantStatus: http.StatusForbidden},
		"refuses a token without the scope":    {token: signToken(t, key, withClaim("scope", "crm-accounts/other")), wantStatus: http.StatusForbidden},
		"refuses a user's token":               {token: signToken(t, key, userClaims()), wantStatus: http.StatusForbidden},
		"refuses a service token with groups":  {token: signToken(t, key, withClaim("cognito:groups", []string{"ADMIN"})), wantStatus: http.StatusForbidden},
		"refuses an expired token":             {token: signToken(t, key, withClaim("exp", time.Now().Add(-time.Hour).Unix())), wantStatus: http.StatusUnauthorized},
		"refuses another user pool":            {token: signToken(t, key, withClaim("iss", "https://cognito-idp.example/other")), wantStatus: http.StatusUnauthorized},
		"refuses a request without a token":    {wantStatus: http.StatusUnauthorized},
		"refuses a token with a bad signature": {token: signToken(t, key, serviceClaims()) + "x", wantStatus: http.StatusUnauthorized},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			assert.Equal(t, test.wantStatus, serve(t, middleware, test.token))
		})
	}

	t.Run("refuses every caller when no service client is configured", func(t *testing.T) {
		closed := verifier.ServiceMiddleware(nil, testScope)
		assert.Equal(t, http.StatusForbidden, serve(t, closed, signToken(t, key, serviceClaims())))
	})
}

func TestUserMiddlewareRefusesServiceTokens(t *testing.T) {
	verifier, key := newTestVerifier(t)

	assert.Equal(t, http.StatusOK, serve(t, verifier.Middleware(), signToken(t, key, userClaims())))
	assert.Equal(t, http.StatusUnauthorized, serve(t, verifier.Middleware(), signToken(t, key, serviceClaims())))
}
