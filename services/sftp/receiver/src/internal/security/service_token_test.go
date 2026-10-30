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

func TestServiceTokenMiddleware(t *testing.T) {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	require.NoError(t, err)
	now := time.Now().Unix()
	verifier := &ServiceTokenVerifier{
		issuer:  "https://cognito-idp.ap-southeast-1.amazonaws.com/ap-southeast-1_test",
		clients: map[string]struct{}{"health-client": {}},
		scope:   "crm-transactions/read",
		keys:    map[string]*rsa.PublicKey{"key-1": &key.PublicKey},
	}
	makeToken := func(clientID, scope string, groups []string) string {
		t.Helper()
		encode := func(value any) string {
			data, err := json.Marshal(value)
			require.NoError(t, err)
			return base64.RawURLEncoding.EncodeToString(data)
		}
		header := encode(map[string]string{"alg": "RS256", "kid": "key-1"})
		claims := encode(map[string]any{
			"iss": verifier.issuer, "sub": clientID, "client_id": clientID,
			"token_use": "access", "scope": scope, "cognito:groups": groups,
			"iat": now, "exp": now + 3600,
		})
		unsigned := header + "." + claims
		digest := sha256.Sum256([]byte(unsigned))
		signature, err := rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, digest[:])
		require.NoError(t, err)
		return unsigned + "." + base64.RawURLEncoding.EncodeToString(signature)
	}

	tests := []struct {
		name  string
		token string
		want  int
	}{
		{name: "missing service token", want: http.StatusUnauthorized},
		{name: "allowed client and exact scope", token: makeToken("health-client", "crm-transactions/read", nil), want: http.StatusNoContent},
		{name: "unlisted client", token: makeToken("other-client", "crm-transactions/read", nil), want: http.StatusForbidden},
		{name: "extra scope", token: makeToken("health-client", "crm-transactions/read crm-accounts/lookup", nil), want: http.StatusForbidden},
		{name: "user groups", token: makeToken("health-client", "crm-transactions/read", []string{"AGENT"}), want: http.StatusForbidden},
	}
	gin.SetMode(gin.TestMode)
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			router := gin.New()
			router.GET("/", verifier.Middleware(), func(c *gin.Context) { c.Status(http.StatusNoContent) })
			request := httptest.NewRequest(http.MethodGet, "/", nil)
			if test.token != "" {
				request.Header.Set("Authorization", "Bearer "+test.token)
			}
			response := httptest.NewRecorder()
			router.ServeHTTP(response, request)
			assert.Equal(t, test.want, response.Code)
		})
	}
}
