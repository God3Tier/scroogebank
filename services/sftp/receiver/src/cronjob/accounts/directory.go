// Package accounts asks account-service which imported account references are
// CRM accounts (ADR-0037). Accounts live in account-service's own database, so
// the import cannot look them up itself. It has no user token, so it
// authenticates with a Cognito client-credentials token (ADR-0025).
package accounts

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"
)

// batchSize is account-service's limit per lookup request.
const batchSize = 500

type Config struct {
	BaseURL      string
	TokenURL     string
	ClientID     string
	ClientSecret string
	Scope        string
}

func LoadConfig() (Config, error) {
	cfg := Config{
		BaseURL:      strings.TrimRight(strings.TrimSpace(os.Getenv("ACCOUNT_SERVICE_BASE_URL")), "/"),
		TokenURL:     strings.TrimSpace(os.Getenv("COGNITO_TOKEN_URL")),
		ClientID:     strings.TrimSpace(os.Getenv("COGNITO_INGEST_CLIENT_ID")),
		ClientSecret: os.Getenv("COGNITO_INGEST_CLIENT_SECRET"),
		Scope:        strings.TrimSpace(os.Getenv("COGNITO_INGEST_SCOPE")),
	}
	if cfg.Scope == "" {
		cfg.Scope = "crm-accounts/lookup"
	}
	for name, value := range map[string]string{
		"ACCOUNT_SERVICE_BASE_URL":     cfg.BaseURL,
		"COGNITO_TOKEN_URL":            cfg.TokenURL,
		"COGNITO_INGEST_CLIENT_ID":     cfg.ClientID,
		"COGNITO_INGEST_CLIENT_SECRET": cfg.ClientSecret,
	} {
		if value == "" {
			return Config{}, fmt.Errorf("%s is required to link imported transactions to accounts", name)
		}
	}
	return cfg, nil
}

type Directory struct {
	config Config
	http   *http.Client

	mu     sync.Mutex
	token  string
	expiry time.Time
}

func New(cfg Config) *Directory {
	return &Directory{config: cfg, http: &http.Client{Timeout: 5 * time.Second}}
}

// Lookup returns, for each of accountIDs that is a CRM account, its client ID.
// Deleted accounts are not returned (ADR-0036). Any failure is an error: the
// caller must not treat "could not ask" as "no such account".
func (d *Directory) Lookup(ctx context.Context, accountIDs []string) (map[string]string, error) {
	found := make(map[string]string, len(accountIDs))
	for start := 0; start < len(accountIDs); start += batchSize {
		if err := d.lookupBatch(ctx, accountIDs[start:min(start+batchSize, len(accountIDs))], found); err != nil {
			return nil, err
		}
	}
	return found, nil
}

func (d *Directory) lookupBatch(ctx context.Context, ids []string, found map[string]string) error {
	token, err := d.accessToken(ctx)
	if err != nil {
		return err
	}
	body, err := json.Marshal(map[string][]string{"ids": ids})
	if err != nil {
		return fmt.Errorf("encode account lookup: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, d.config.BaseURL+"/internal/accounts/lookup", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("build account lookup: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")

	res, err := d.http.Do(req)
	if err != nil {
		return fmt.Errorf("call account-service: %w", err)
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("account-service lookup returned %d", res.StatusCode)
	}
	var payload struct {
		Accounts []struct {
			ID       string `json:"id"`
			ClientID string `json:"client_id"`
		} `json:"accounts"`
	}
	if err := json.NewDecoder(io.LimitReader(res.Body, 1<<20)).Decode(&payload); err != nil || payload.Accounts == nil {
		return fmt.Errorf("account-service lookup returned no account list")
	}
	for _, account := range payload.Accounts {
		found[strings.ToLower(account.ID)] = account.ClientID
	}
	return nil
}

// accessToken reuses a token until a minute before it expires.
func (d *Directory) accessToken(ctx context.Context) (string, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.token != "" && time.Now().Before(d.expiry) {
		return d.token, nil
	}

	form := url.Values{"grant_type": {"client_credentials"}, "scope": {d.config.Scope}}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, d.config.TokenURL, strings.NewReader(form.Encode()))
	if err != nil {
		return "", fmt.Errorf("build token request: %w", err)
	}
	req.SetBasicAuth(d.config.ClientID, d.config.ClientSecret)
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	res, err := d.http.Do(req)
	if err != nil {
		return "", fmt.Errorf("request Cognito token: %w", err)
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return "", fmt.Errorf("Cognito token endpoint returned %d", res.StatusCode)
	}
	var token struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if err := json.NewDecoder(io.LimitReader(res.Body, 64<<10)).Decode(&token); err != nil || token.AccessToken == "" {
		return "", fmt.Errorf("Cognito token endpoint returned no access token")
	}
	d.token = token.AccessToken
	d.expiry = time.Now().Add(time.Duration(token.ExpiresIn)*time.Second - time.Minute)
	return d.token, nil
}
