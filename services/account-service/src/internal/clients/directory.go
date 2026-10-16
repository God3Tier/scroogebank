// Package clients asks client-profile-management-service about a client.
//
// Accounts and clients live in separate databases, so the accounts table has
// no foreign key to clients. This lookup replaces it: an account is created
// only after client-profile-management-service confirms the client exists and
// that the caller may access it, and the client's owning agent is copied onto
// the account at that moment (ADR-0035).
package clients

import (
	accounterrors "account-service/src/internal/errors"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// maxClientResponseBytes bounds how much of a client profile is read. A
// profile is well under 4 KiB; the limit only guards against a misbehaving
// upstream.
const maxClientResponseBytes = 64 << 10

type Directory interface {
	// OwnerOf returns the agent who owns the client. It forwards the caller's
	// Authorization header, so client-profile-management-service applies its
	// own ownership rule: admins see every client, and an agent asking about
	// another agent's client gets the same 404 as for a missing one.
	OwnerOf(ctx context.Context, clientID, authorization string) (string, error)
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

func (d httpDirectory) OwnerOf(ctx context.Context, clientID, authorization string) (string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, d.baseURL+"/api/clients/"+url.PathEscape(clientID), nil)
	if err != nil {
		return "", fmt.Errorf("%w: build request: %v", accounterrors.ErrClientServiceUnavailable, err)
	}
	req.Header.Set("Authorization", authorization)
	req.Header.Set("Accept", "application/json")

	res, err := d.client.Do(req)
	if err != nil {
		return "", fmt.Errorf("%w: %v", accounterrors.ErrClientServiceUnavailable, err)
	}
	defer res.Body.Close()

	switch res.StatusCode {
	case http.StatusOK:
		var client struct {
			CreatedByAgentID string `json:"created_by_agent_id"`
		}
		if err := json.NewDecoder(io.LimitReader(res.Body, maxClientResponseBytes)).Decode(&client); err != nil {
			return "", fmt.Errorf("%w: unreadable client profile: %v", accounterrors.ErrClientServiceUnavailable, err)
		}
		owner := strings.TrimSpace(client.CreatedByAgentID)
		if owner == "" {
			return "", fmt.Errorf("%w: client profile has no owning agent", accounterrors.ErrClientServiceUnavailable)
		}
		return owner, nil
	case http.StatusNotFound:
		return "", accounterrors.ErrClientNotFound
	default:
		// The caller's token already passed this service's own verification,
		// so any other status (including 401 or 403) means the two services
		// disagree or the client service is failing. Account creation fails
		// closed rather than guessing.
		return "", fmt.Errorf("%w: client service returned %d", accounterrors.ErrClientServiceUnavailable, res.StatusCode)
	}
}
