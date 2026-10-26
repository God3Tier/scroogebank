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

func userClaims(groups ...string) map[string]any {
	now := time.Now().Unix()
	return map[string]any{
		"iss": testIssuer, "sub": "agent-sub", "client_id": testUserClient, "token_use": "access",
		"cognito:groups": groups, "scope": "aws.cognito.signin.user.admin", "iat": now, "exp": now + 3600,
	}
}

// A client-credentials token: Cognito sets sub to the app client ID and adds
// the granted scopes, with no groups.
func serviceClaims() map[string]any {
	now := time.Now().Unix()
	return map[string]any{
		"iss": testIssuer, "sub": testServiceClient, "client_id": testServiceClient, "token_use": "access",
		"scope": "crm-accounts/lookup", "iat": now, "exp": now + 3600,
	}
}

func TestMiddleware(t *testing.T) {
	verifier, key := newTestVerifier(t)
	withClaim := func(name string, value any) map[string]any {
		claims := userClaims("AGENT")
		claims[name] = value
		return claims
	}

	tests := map[string]struct {
		token      string
		wantStatus int
	}{
		"admits an agent":                      {token: signToken(t, key, userClaims("AGENT")), wantStatus: http.StatusOK},
		"admits an admin":                      {token: signToken(t, key, userClaims("ADMIN")), wantStatus: http.StatusOK},
		"refuses a user with no CRM group":     {token: signToken(t, key, userClaims()), wantStatus: http.StatusForbidden},
		"refuses a service token":              {token: signToken(t, key, serviceClaims()), wantStatus: http.StatusUnauthorized},
		"refuses another app client":           {token: signToken(t, key, withClaim("client_id", "someone-else")), wantStatus: http.StatusUnauthorized},
		"refuses an ID token":                  {token: signToken(t, key, withClaim("token_use", "id")), wantStatus: http.StatusUnauthorized},
		"refuses an expired token":             {token: signToken(t, key, withClaim("exp", time.Now().Add(-time.Hour).Unix())), wantStatus: http.StatusUnauthorized},
		"refuses another user pool":            {token: signToken(t, key, withClaim("iss", "https://cognito-idp.example/other")), wantStatus: http.StatusUnauthorized},
		"refuses a request without a token":    {wantStatus: http.StatusUnauthorized},
		"refuses a token with a bad signature": {token: signToken(t, key, userClaims("AGENT")) + "x", wantStatus: http.StatusUnauthorized},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			gin.SetMode(gin.TestMode)
			router := gin.New()
			router.GET("/", verifier.Middleware(), func(c *gin.Context) { c.Status(http.StatusOK) })
			req := httptest.NewRequest(http.MethodGet, "/", nil)
			if test.token != "" {
				req.Header.Set("Authorization", "Bearer "+test.token)
			}
			res := httptest.NewRecorder()

			router.ServeHTTP(res, req)

			assert.Equal(t, test.wantStatus, res.Code)
		})
	}
}

// The handlers see the verified caller and the token they forward.
func TestMiddlewareKeepsTheCallerAndToken(t *testing.T) {
	verifier, key := newTestVerifier(t)
	token := signToken(t, key, userClaims("ADMIN"))
	var principal Principal
	var forwarded string
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.GET("/", verifier.Middleware(), func(c *gin.Context) {
		principal, _ = PrincipalFromGin(c)
		forwarded = AccessToken(c)
	})
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Header.Set("Authorization", "Bearer "+token)

	router.ServeHTTP(httptest.NewRecorder(), req)

	assert.Equal(t, Principal{Subject: "agent-sub", Role: "ADMIN"}, principal)
	assert.Equal(t, token, forwarded)
}

func TestRequireAdmin(t *testing.T) {
	gin.SetMode(gin.TestMode)

	tests := map[string]struct {
		principal  *Principal
		wantStatus int
	}{
		"an admin reaches the handler":   {principal: &Principal{Subject: "admin-sub", Role: "ADMIN"}, wantStatus: http.StatusOK},
		"an agent is refused":            {principal: &Principal{Subject: "agent-sub", Role: "AGENT"}, wantStatus: http.StatusForbidden},
		"an unauthenticated request too": {wantStatus: http.StatusForbidden},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			router := gin.New()
			router.Use(func(c *gin.Context) {
				if test.principal != nil {
					SetCaller(c, *test.principal, "token")
				}
				c.Next()
			})
			router.GET("/api/transactions", RequireAdmin(), func(c *gin.Context) {
				c.Status(http.StatusOK)
			})
			res := httptest.NewRecorder()

			router.ServeHTTP(res, httptest.NewRequest(http.MethodGet, "/api/transactions", nil))

			assert.Equal(t, test.wantStatus, res.Code)
		})
	}
}
