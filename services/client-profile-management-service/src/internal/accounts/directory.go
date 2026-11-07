// Package accounts asks account-service about a client's accounts.
//
// Accounts used to share this service's database, and a foreign key with
// ON DELETE RESTRICT stopped a client with accounts from being deleted. The
// accounts now live in account-service's own database, so that rule is kept
// by asking account-service before deleting (ADR-0017, ADR-0035).
package accounts

import (
	"bytes"
	clienterrors "client-profile-management-service/src/internal/errors"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// maxAccountsResponseBytes bounds how much of the account list is read. A
// client holds at most one account per type (uk_accounts_client_type), so the
// list is a handful of rows; the limit only guards against a misbehaving
// upstream.
const maxAccountsResponseBytes = 1 << 20

type Directory interface {
	// HasAccounts reports whether the client has any account that is not
	// deleted. It forwards the caller's Authorization header, so account-service
	// scopes the answer to what the caller may see: an admin sees every account,
	// and an agent sees the accounts of their own clients, which are all of that
	// client's.
	HasAccounts(ctx context.Context, clientID, authorization string) (bool, error)
	// SetOwner gives every account of the clients to ownerAgentID, while
	// those clients are reassigned (ADR-0060). It forwards the admin's
	// Authorization header; account-service answers admins only. Anything but
	// a confirmation is ErrAccountServiceUnavailable, so nothing is reassigned.
	SetOwner(ctx context.Context, clientIDs []string, ownerAgentID, authorization string) error
}

type httpDirectory struct {
	baseURL string
	client  *http.Client
}

func NewHTTPDirectory(baseURL string, timeout time.Duration) Directory {
	return httpDirectory{
		baseURL: strings.TrimRight(strings.TrimSpace(baseURL), "/"),
		client:  &http.Client{Timeout: timeout},
	}
}

func (d httpDirectory) HasAccounts(ctx context.Context, clientID, authorization string) (bool, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, d.baseURL+"/api/accounts?clientId="+url.QueryEscape(clientID), nil)
	if err != nil {
		return false, fmt.Errorf("%w: build request: %v", clienterrors.ErrAccountServiceUnavailable, err)
	}
	req.Header.Set("Authorization", authorization)
	req.Header.Set("Accept", "application/json")

	res, err := d.client.Do(req)
	if err != nil {
		return false, fmt.Errorf("%w: %v", clienterrors.ErrAccountServiceUnavailable, err)
	}
	defer res.Body.Close()

	if res.StatusCode != http.StatusOK {
		// Anything but a list means the answer is unknown. Deletion fails
		// closed rather than risk orphaning accounts.
		return false, fmt.Errorf("%w: account service returned %d", clienterrors.ErrAccountServiceUnavailable, res.StatusCode)
	}
	var accounts []json.RawMessage
	if err := json.NewDecoder(io.LimitReader(res.Body, maxAccountsResponseBytes)).Decode(&accounts); err != nil {
		return false, fmt.Errorf("%w: unreadable account list: %v", clienterrors.ErrAccountServiceUnavailable, err)
	}
	if accounts == nil {
		// account-service answers an empty list with [], never null. A null
		// is not evidence of "no accounts", so it must not permit a delete.
		return false, fmt.Errorf("%w: account list was null", clienterrors.ErrAccountServiceUnavailable)
	}
	return len(accounts) > 0, nil
}

func (d httpDirectory) SetOwner(ctx context.Context, clientIDs []string, ownerAgentID, authorization string) error {
	body, err := json.Marshal(map[string]any{"client_ids": clientIDs, "owner_agent_id": ownerAgentID})
	if err != nil {
		return fmt.Errorf("%w: encode request: %v", clienterrors.ErrAccountServiceUnavailable, err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPut, d.baseURL+"/ownership/accounts", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("%w: build request: %v", clienterrors.ErrAccountServiceUnavailable, err)
	}
	req.Header.Set("Authorization", authorization)
	req.Header.Set("Content-Type", "application/json")

	res, err := d.client.Do(req)
	if err != nil {
		return fmt.Errorf("%w: %v", clienterrors.ErrAccountServiceUnavailable, err)
	}
	defer res.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(res.Body, maxAccountsResponseBytes))

	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("%w: account service returned %d", clienterrors.ErrAccountServiceUnavailable, res.StatusCode)
	}
	return nil
}
