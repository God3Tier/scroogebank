package security

import (
	"crypto"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
)

// ServiceTokenVerifier accepts only Cognito client-credentials tokens from
// configured app clients with the one required transaction-read scope.
type ServiceTokenVerifier struct {
	issuer         string
	clients        map[string]struct{}
	scope          string
	keysURL        string
	client         *http.Client
	mu             sync.RWMutex
	refreshMu      sync.Mutex
	keys           map[string]*rsa.PublicKey
	lastKeyRefresh time.Time
}

type serviceTokenClaims struct {
	Issuer    string   `json:"iss"`
	ClientID  string   `json:"client_id"`
	Scope     string   `json:"scope"`
	TokenUse  string   `json:"token_use"`
	Groups    []string `json:"cognito:groups"`
	IssuedAt  int64    `json:"iat"`
	NotBefore int64    `json:"nbf"`
	Expires   int64    `json:"exp"`
}

type serviceTokenKey struct {
	KeyID    string `json:"kid"`
	KeyType  string `json:"kty"`
	Use      string `json:"use"`
	Modulus  string `json:"n"`
	Exponent string `json:"e"`
}

// NewServiceTokenVerifier loads Cognito signing keys during startup. An outage
// or invalid JWKS stops the service rather than weakening authentication.
func NewServiceTokenVerifier(issuer string, clientIDs []string, scope string) (*ServiceTokenVerifier, error) {
	issuer = strings.TrimRight(strings.TrimSpace(issuer), "/")
	parsed, err := url.Parse(issuer)
	if err != nil || parsed.Scheme != "https" || !strings.HasPrefix(parsed.Hostname(), "cognito-idp.") ||
		!(strings.HasSuffix(parsed.Hostname(), ".amazonaws.com") || strings.HasSuffix(parsed.Hostname(), ".amazonaws.com.cn")) || parsed.Path == "" {
		return nil, errors.New("COGNITO_ISSUER_URI must be an HTTPS Cognito user-pool issuer URL")
	}
	if len(clientIDs) == 0 || strings.TrimSpace(scope) == "" {
		return nil, errors.New("allowed service clients and required scope are required")
	}
	keysURL, err := url.JoinPath(issuer, ".well-known", "jwks.json")
	if err != nil {
		return nil, fmt.Errorf("build Cognito JWKS URL: %w", err)
	}
	verifier := &ServiceTokenVerifier{
		issuer: issuer, clients: make(map[string]struct{}, len(clientIDs)), scope: scope,
		keysURL: keysURL, client: &http.Client{Timeout: 5 * time.Second},
	}
	for _, clientID := range clientIDs {
		if value := strings.TrimSpace(clientID); value != "" {
			verifier.clients[value] = struct{}{}
		}
	}
	if len(verifier.clients) == 0 {
		return nil, errors.New("at least one allowed Cognito client ID is required")
	}
	if err := verifier.refreshKeys(); err != nil {
		return nil, err
	}
	return verifier, nil
}

func (v *ServiceTokenVerifier) refreshKeys() error {
	request, err := http.NewRequest(http.MethodGet, v.keysURL, nil)
	if err != nil {
		return fmt.Errorf("build Cognito JWKS request: %w", err)
	}
	response, err := v.client.Do(request)
	if err != nil {
		return fmt.Errorf("load Cognito signing keys: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("Cognito JWKS returned HTTP %d", response.StatusCode)
	}
	var document struct {
		Keys []serviceTokenKey `json:"keys"`
	}
	if err := json.NewDecoder(io.LimitReader(response.Body, 1<<20)).Decode(&document); err != nil {
		return fmt.Errorf("decode Cognito JWKS: %w", err)
	}
	keys := make(map[string]*rsa.PublicKey, len(document.Keys))
	for _, key := range document.Keys {
		if key.KeyType != "RSA" || key.Use != "sig" {
			continue
		}
		modulus, errN := base64.RawURLEncoding.DecodeString(key.Modulus)
		exponent, errE := base64.RawURLEncoding.DecodeString(key.Exponent)
		if errN != nil || errE != nil || len(exponent) == 0 {
			continue
		}
		exponentValue := 0
		for _, part := range exponent {
			exponentValue = exponentValue<<8 | int(part)
		}
		if exponentValue < 3 || exponentValue%2 == 0 {
			continue
		}
		keys[key.KeyID] = &rsa.PublicKey{N: new(big.Int).SetBytes(modulus), E: exponentValue}
	}
	if len(keys) == 0 {
		return errors.New("Cognito JWKS contains no usable RSA signing keys")
	}
	v.mu.Lock()
	v.keys = keys
	v.lastKeyRefresh = time.Now()
	v.mu.Unlock()
	return nil
}

func (v *ServiceTokenVerifier) Middleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		parts := strings.Fields(c.GetHeader("Authorization"))
		if len(parts) != 2 || !strings.EqualFold(parts[0], "Bearer") {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Bearer service token required"})
			return
		}
		if !v.verify(parts[1]) {
			c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "transaction read scope required"})
			return
		}
		c.Next()
	}
}

func (v *ServiceTokenVerifier) verify(token string) bool {
	parts := strings.Split(token, ".")
	if len(parts) != 3 || len(token) > 16*1024 {
		return false
	}
	var header struct {
		Algorithm string `json:"alg"`
		KeyID     string `json:"kid"`
	}
	if decodeTokenPart(parts[0], &header) != nil || header.Algorithm != "RS256" {
		return false
	}
	key := v.key(header.KeyID)
	if key == nil {
		return false
	}
	signature, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		return false
	}
	digest := sha256.Sum256([]byte(parts[0] + "." + parts[1]))
	if rsa.VerifyPKCS1v15(key, crypto.SHA256, digest[:], signature) != nil {
		return false
	}
	var claims serviceTokenClaims
	if decodeTokenPart(parts[1], &claims) != nil {
		return false
	}
	now := time.Now().Unix()
	_, allowedClient := v.clients[claims.ClientID]
	return claims.Issuer == v.issuer && claims.TokenUse == "access" && allowedClient &&
		len(claims.Groups) == 0 && hasOnlyScope(claims.Scope, v.scope) && claims.IssuedAt > 0 && claims.IssuedAt <= now+30 &&
		(claims.NotBefore == 0 || claims.NotBefore <= now+30) && claims.Expires > now-30
}

func (v *ServiceTokenVerifier) key(keyID string) *rsa.PublicKey {
	v.mu.RLock()
	key := v.keys[keyID]
	v.mu.RUnlock()
	if key != nil {
		return key
	}

	v.refreshMu.Lock()
	defer v.refreshMu.Unlock()
	v.mu.RLock()
	key = v.keys[keyID]
	refreshAllowed := time.Since(v.lastKeyRefresh) >= 30*time.Second
	v.mu.RUnlock()
	if key != nil || !refreshAllowed {
		return key
	}
	// Rate-limit failed refresh attempts too, so unknown key IDs cannot turn
	// an unavailable JWKS endpoint into an unbounded outbound request stream.
	v.mu.Lock()
	v.lastKeyRefresh = time.Now()
	v.mu.Unlock()
	if v.refreshKeys() != nil {
		return nil
	}
	v.mu.RLock()
	defer v.mu.RUnlock()
	return v.keys[keyID]
}

func hasOnlyScope(scopes, required string) bool {
	values := strings.Fields(scopes)
	return len(values) == 1 && values[0] == required
}

func decodeTokenPart(part string, target any) error {
	data, err := base64.RawURLEncoding.DecodeString(part)
	if err != nil {
		return err
	}
	return json.Unmarshal(data, target)
}
