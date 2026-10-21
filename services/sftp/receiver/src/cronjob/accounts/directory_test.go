package accounts

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// fakeServices plays both Cognito's token endpoint and account-service.
type fakeServices struct {
	tokenCalls   int
	lookupSizes  []int
	lookupStatus int
	lookupBody   string
	tokenStatus  int
}

func (f *fakeServices) start(t *testing.T) Config {
	t.Helper()
	mux := http.NewServeMux()
	mux.HandleFunc("POST /oauth2/token", func(w http.ResponseWriter, r *http.Request) {
		f.tokenCalls++
		clientID, secret, ok := r.BasicAuth()
		if f.tokenStatus != 0 || !ok || clientID != "ingest-client" || secret != "ingest-secret" ||
			r.FormValue("grant_type") != "client_credentials" || r.FormValue("scope") != "crm-accounts/lookup" {
			w.WriteHeader(max(f.tokenStatus, http.StatusBadRequest))
			return
		}
		_, _ = fmt.Fprint(w, `{"access_token":"service-token","expires_in":3600,"token_type":"Bearer"}`)
	})
	mux.HandleFunc("POST /internal/accounts/lookup", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer service-token" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		var request struct {
			IDs []string `json:"ids"`
		}
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		f.lookupSizes = append(f.lookupSizes, len(request.IDs))
		if f.lookupStatus != 0 {
			w.WriteHeader(f.lookupStatus)
			return
		}
		if f.lookupBody != "" {
			_, _ = fmt.Fprint(w, f.lookupBody)
			return
		}
		_, _ = fmt.Fprint(w, `{"accounts":[{"id":"A0000000-0000-0000-0000-000000000231","client_id":"client-1"}]}`)
	})
	server := httptest.NewServer(mux)
	t.Cleanup(server.Close)
	return Config{
		BaseURL: server.URL, TokenURL: server.URL + "/oauth2/token",
		ClientID: "ingest-client", ClientSecret: "ingest-secret", Scope: "crm-accounts/lookup",
	}
}

func TestLookup(t *testing.T) {
	ctx := context.Background()

	t.Run("returns found accounts keyed by lowercase ID and reuses the token", func(t *testing.T) {
		fake := &fakeServices{}
		directory := New(fake.start(t))

		for range 2 {
			found, err := directory.Lookup(ctx, []string{"a0000000-0000-0000-0000-000000000231"})
			require.NoError(t, err)
			assert.Equal(t, map[string]string{"a0000000-0000-0000-0000-000000000231": "client-1"}, found)
		}
		assert.Equal(t, 1, fake.tokenCalls)
	})

	t.Run("sends large lists in batches", func(t *testing.T) {
		fake := &fakeServices{lookupBody: `{"accounts":[]}`}
		ids := make([]string, batchSize+1)
		for i := range ids {
			ids[i] = fmt.Sprintf("00000000-0000-0000-0000-%012d", i)
		}

		_, err := New(fake.start(t)).Lookup(ctx, ids)

		require.NoError(t, err)
		assert.Equal(t, []int{batchSize, 1}, fake.lookupSizes)
	})

	failures := map[string]*fakeServices{
		"Cognito refuses the client":    {tokenStatus: http.StatusUnauthorized},
		"account-service refuses":       {lookupStatus: http.StatusForbidden},
		"account-service errors":        {lookupStatus: http.StatusInternalServerError},
		"account-service sends no list": {lookupBody: `{"accounts":null}`},
		"account-service sends garbage": {lookupBody: `{`},
	}
	for name, fake := range failures {
		t.Run("fails when "+name, func(t *testing.T) {
			found, err := New(fake.start(t)).Lookup(ctx, []string{"a0000000-0000-0000-0000-000000000231"})

			assert.Error(t, err)
			assert.Nil(t, found)
		})
	}

	t.Run("fails when account-service is down", func(t *testing.T) {
		cfg := (&fakeServices{}).start(t)
		cfg.BaseURL = "http://127.0.0.1:1"

		_, err := New(cfg).Lookup(ctx, []string{"a0000000-0000-0000-0000-000000000231"})

		assert.Error(t, err)
	})
}

func TestLoadConfigRequiresTheServiceCredentials(t *testing.T) {
	t.Setenv("ACCOUNT_SERVICE_BASE_URL", "http://localhost:8083/")
	t.Setenv("COGNITO_TOKEN_URL", "https://example.auth.ap-southeast-1.amazoncognito.com/oauth2/token")
	t.Setenv("COGNITO_INGEST_CLIENT_ID", "ingest-client")
	t.Setenv("COGNITO_INGEST_CLIENT_SECRET", "ingest-secret")
	t.Setenv("COGNITO_INGEST_SCOPE", "")

	cfg, err := LoadConfig("", "")
	require.NoError(t, err)
	assert.Equal(t, "http://localhost:8083", cfg.BaseURL)
	assert.Equal(t, "crm-accounts/lookup", cfg.Scope)

	t.Setenv("COGNITO_INGEST_CLIENT_SECRET", "")
	_, err = LoadConfig("", "")
	assert.ErrorContains(t, err, "COGNITO_INGEST_CLIENT_SECRET")
}

func TestLoadConfigPrefersCredentialsFromSecretsManager(t *testing.T) {
	t.Setenv("ACCOUNT_SERVICE_BASE_URL", "http://localhost:8083")
	t.Setenv("COGNITO_TOKEN_URL", "https://example.auth.ap-southeast-1.amazoncognito.com/oauth2/token")
	t.Setenv("COGNITO_INGEST_CLIENT_ID", "")
	t.Setenv("COGNITO_INGEST_CLIENT_SECRET", "")

	cfg, err := LoadConfig("secret-client", "secret-value")
	require.NoError(t, err)
	assert.Equal(t, "secret-client", cfg.ClientID)
	assert.Equal(t, "secret-value", cfg.ClientSecret)
}
