// Package security checks transaction-service's callers. The verifier is a
// copy of account-service's, without its service-token middleware (ADR-0064
// decision 1, ADR-0037).
package security

import (
	"context"
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
	"os"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
)

const jwksCacheDuration = 30 * time.Minute
const jwksRefreshMinimumInterval = time.Minute

type Principal struct {
	Subject string
	Role    string
}

type CognitoVerifier struct {
	issuer   string
	clientID string
	audience string
	client   *http.Client

	mu     sync.RWMutex
	keys   map[string]*rsa.PublicKey
	keysAt time.Time
}

type jwtHeader struct {
	Algorithm string `json:"alg"`
	KeyID     string `json:"kid"`
	Type      string `json:"typ"`
}

type cognitoClaims struct {
	Issuer    string   `json:"iss"`
	Subject   string   `json:"sub"`
	ClientID  string   `json:"client_id"`
	Audience  string   `json:"aud"`
	TokenUse  string   `json:"token_use"`
	Groups    []string `json:"cognito:groups"`
	IssuedAt  int64    `json:"iat"`
	NotBefore int64    `json:"nbf"`
	Expires   int64    `json:"exp"`
}

type jwksDocument struct {
	Keys []jwk `json:"keys"`
}

type jwk struct {
	KeyID     string `json:"kid"`
	KeyType   string `json:"kty"`
	Use       string `json:"use"`
	Algorithm string `json:"alg"`
	Modulus   string `json:"n"`
	Exponent  string `json:"e"`
}

func NewCognitoVerifier(issuer, clientID string) (*CognitoVerifier, error) {
	issuer = strings.TrimRight(strings.TrimSpace(issuer), "/")
	parsedIssuer, err := url.Parse(issuer)
	if err != nil || parsedIssuer.Scheme != "https" ||
		!strings.HasPrefix(parsedIssuer.Hostname(), "cognito-idp.") ||
		!(strings.HasSuffix(parsedIssuer.Hostname(), ".amazonaws.com") || strings.HasSuffix(parsedIssuer.Hostname(), ".amazonaws.com.cn")) ||
		parsedIssuer.Path == "" {
		return nil, errors.New("COGNITO_ISSUER_URI must be an HTTPS Cognito user-pool issuer URL")
	}
	if strings.TrimSpace(clientID) == "" {
		return nil, errors.New("COGNITO_APP_CLIENT_ID is required")
	}

	v := &CognitoVerifier{
		issuer:   issuer,
		clientID: strings.TrimSpace(clientID),
		audience: strings.TrimSpace(os.Getenv("COGNITO_API_AUDIENCE")),
		client:   &http.Client{Timeout: 5 * time.Second},
		keys:     make(map[string]*rsa.PublicKey),
	}
	if err := v.refreshKeys(context.Background(), true); err != nil {
		return nil, fmt.Errorf("load Cognito signing keys: %w", err)
	}
	return v, nil
}

var errInvalidClaims = errors.New("JWT claims are invalid for this Cognito user pool or app client")

// Verify accepts user access tokens issued to the CRM app client.
func (v *CognitoVerifier) Verify(token string) (Principal, error) {
	claims, err := v.verifyToken(token)
	if err != nil {
		return Principal{}, err
	}
	if claims.ClientID != v.clientID {
		return Principal{}, errInvalidClaims
	}

	role := ""
	for _, group := range claims.Groups {
		switch strings.ToUpper(group) {
		case "ADMIN":
			role = "ADMIN"
		case "AGENT":
			if role == "" {
				role = "AGENT"
			}
		}
	}
	return Principal{Subject: claims.Subject, Role: role}, nil
}

// verifyToken checks the signature and the claims every access token from
// this user pool must have.
func (v *CognitoVerifier) verifyToken(token string) (cognitoClaims, error) {
	if len(token) > 16*1024 {
		return cognitoClaims{}, errors.New("JWT is too large")
	}
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return cognitoClaims{}, errors.New("malformed JWT")
	}

	var header jwtHeader
	if err := decodeSegment(parts[0], &header); err != nil || header.Algorithm != "RS256" || header.KeyID == "" || (header.Type != "" && header.Type != "JWT") {
		return cognitoClaims{}, errors.New("unsupported JWT header")
	}
	key, err := v.signingKey(header.KeyID)
	if err != nil {
		return cognitoClaims{}, err
	}
	signature, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		return cognitoClaims{}, errors.New("malformed JWT signature")
	}
	digest := sha256.Sum256([]byte(parts[0] + "." + parts[1]))
	if err := rsa.VerifyPKCS1v15(key, crypto.SHA256, digest[:], signature); err != nil {
		return cognitoClaims{}, errors.New("invalid JWT signature")
	}

	var claims cognitoClaims
	if err := decodeSegment(parts[1], &claims); err != nil {
		return cognitoClaims{}, errors.New("malformed JWT claims")
	}
	now := time.Now().Unix()
	const clockSkew int64 = 30
	if claims.Issuer != v.issuer ||
		(claims.Audience != "" && (v.audience == "" || claims.Audience != v.audience)) || claims.TokenUse != "access" ||
		claims.Subject == "" || len(claims.Subject) > 128 ||
		claims.Expires <= now-clockSkew || claims.IssuedAt == 0 || claims.IssuedAt > now+clockSkew ||
		(claims.NotBefore != 0 && claims.NotBefore > now+clockSkew) {
		return cognitoClaims{}, errInvalidClaims
	}
	return claims, nil
}

func bearerToken(c *gin.Context) (string, bool) {
	authorization := strings.Fields(c.GetHeader("Authorization"))
	if len(authorization) != 2 || !strings.EqualFold(authorization[0], "Bearer") {
		return "", false
	}
	return authorization[1], true
}

// Middleware admits user access tokens issued to the CRM app client whose
// user is in the ADMIN or AGENT group (ADR-0064 decision 1). It keeps the
// token, which the routes forward to account-service to check ownership.
func (v *CognitoVerifier) Middleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		token, ok := bearerToken(c)
		if !ok {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "Bearer access token required"})
			return
		}
		principal, err := v.Verify(token)
		if err != nil {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid access token"})
			return
		}
		if principal.Role == "" {
			c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "ADMIN or AGENT role required"})
			return
		}
		SetCaller(c, principal, token)
		c.Next()
	}
}

func decodeSegment(segment string, target any) error {
	encoded, err := base64.RawURLEncoding.DecodeString(segment)
	if err != nil {
		return err
	}
	return json.Unmarshal(encoded, target)
}

func (v *CognitoVerifier) signingKey(keyID string) (*rsa.PublicKey, error) {
	v.mu.RLock()
	key, found := v.keys[keyID]
	fresh := time.Since(v.keysAt) < jwksCacheDuration
	v.mu.RUnlock()
	if found && fresh {
		return key, nil
	}

	if err := v.refreshKeys(context.Background(), true); err != nil {
		return nil, fmt.Errorf("refresh Cognito signing keys: %w", err)
	}
	v.mu.RLock()
	defer v.mu.RUnlock()
	key, found = v.keys[keyID]
	if !found {
		return nil, errors.New("unknown Cognito signing key")
	}
	return key, nil
}

func (v *CognitoVerifier) refreshKeys(ctx context.Context, force bool) error {
	v.mu.Lock()
	defer v.mu.Unlock()

	if len(v.keys) > 0 {
		age := time.Since(v.keysAt)
		if !force && age < jwksCacheDuration {
			return nil
		}
		// Unknown kid values are untrusted input. Avoid a JWKS network request
		// per invalid token while still allowing timely key rotation.
		if force && age < jwksRefreshMinimumInterval {
			return nil
		}
	}
	keysURL, err := url.JoinPath(v.issuer, ".well-known", "jwks.json")
	if err != nil {
		return err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, keysURL, nil)
	if err != nil {
		return err
	}
	response, err := v.client.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("Cognito JWKS endpoint returned HTTP %d", response.StatusCode)
	}
	var document jwksDocument
	if err := json.NewDecoder(io.LimitReader(response.Body, 1<<20)).Decode(&document); err != nil {
		return fmt.Errorf("decode Cognito JWKS: %w", err)
	}
	keys := make(map[string]*rsa.PublicKey, len(document.Keys))
	for _, candidate := range document.Keys {
		if candidate.KeyID == "" || candidate.KeyType != "RSA" ||
			(candidate.Use != "" && candidate.Use != "sig") ||
			(candidate.Algorithm != "" && candidate.Algorithm != "RS256") {
			continue
		}
		publicKey, err := rsaPublicKey(candidate.Modulus, candidate.Exponent)
		if err != nil {
			continue
		}
		keys[candidate.KeyID] = publicKey
	}
	if len(keys) == 0 {
		return errors.New("Cognito JWKS contains no usable RSA signing keys")
	}
	v.keys = keys
	v.keysAt = time.Now()
	return nil
}

func rsaPublicKey(modulus, exponent string) (*rsa.PublicKey, error) {
	nBytes, err := base64.RawURLEncoding.DecodeString(modulus)
	if err != nil {
		return nil, err
	}
	eBytes, err := base64.RawURLEncoding.DecodeString(exponent)
	if err != nil {
		return nil, err
	}
	n := new(big.Int).SetBytes(nBytes)
	e := new(big.Int).SetBytes(eBytes)
	if n.Sign() <= 0 || !e.IsInt64() || e.Int64() <= 1 || e.Int64() > 0x7fffffff {
		return nil, errors.New("invalid RSA modulus or exponent")
	}
	return &rsa.PublicKey{N: n, E: int(e.Int64())}, nil
}
