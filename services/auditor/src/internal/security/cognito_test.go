package security

import (
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const (
	testIssuer = "https://cognito-idp.ap-southeast-1.amazonaws.com/ap-southeast-1_test"
	testClient = "crm-web-client"
)

// The verifier's key cache is seeded with a test key, so no request reaches
// Cognito: the tokens below are signed exactly as Cognito would sign them.
func newTestVerifier(t *testing.T) (*CognitoVerifier, *rsa.PrivateKey) {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	require.NoError(t, err)
	return &CognitoVerifier{
		issuer:   testIssuer,
		clientID: testClient,
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
		"iss": testIssuer, "sub": "user-sub", "client_id": testClient, "token_use": "access",
		"cognito:groups": groups, "iat": now, "exp": now + 3600,
	}
}

func TestVerify(t *testing.T) {
	verifier, key := newTestVerifier(t)
	otherKey, err := rsa.GenerateKey(rand.Reader, 2048)
	require.NoError(t, err)
	with := func(change func(map[string]any)) map[string]any {
		claims := userClaims("AGENT")
		change(claims)
		return claims
	}

	tests := map[string]struct {
		token    string
		wantRole string
		wantErr  bool
	}{
		"an agent":                  {token: signToken(t, key, userClaims("AGENT")), wantRole: "AGENT"},
		"an admin":                  {token: signToken(t, key, userClaims("AGENT", "ADMIN")), wantRole: "ADMIN"},
		"a user in no CRM group":    {token: signToken(t, key, userClaims()), wantRole: ""},
		"another app client":        {token: signToken(t, key, with(func(c map[string]any) { c["client_id"] = "other" })), wantErr: true},
		"an ID token":               {token: signToken(t, key, with(func(c map[string]any) { c["token_use"] = "id" })), wantErr: true},
		"another user pool":         {token: signToken(t, key, with(func(c map[string]any) { c["iss"] = testIssuer + "x" })), wantErr: true},
		"an expired token":          {token: signToken(t, key, with(func(c map[string]any) { c["exp"] = time.Now().Add(-time.Hour).Unix() })), wantErr: true},
		"a token signed by another": {token: signToken(t, otherKey, userClaims("ADMIN")), wantErr: true},
		"not a JWT":                 {token: "not-a-jwt", wantErr: true},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			principal, err := verifier.Verify(test.token)
			if test.wantErr {
				assert.Error(t, err)
				return
			}
			require.NoError(t, err)
			assert.Equal(t, "user-sub", principal.Subject)
			assert.Equal(t, test.wantRole, principal.Role)
		})
	}
}

func TestNewCognitoVerifierRequiresACognitoIssuer(t *testing.T) {
	_, err := NewCognitoVerifier("https://example.com/pool", testClient)
	assert.Error(t, err)
	_, err = NewCognitoVerifier(testIssuer, "")
	assert.Error(t, err)
}
