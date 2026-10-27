// Package accounts asks account-service which accounts the caller may read.
//
// account-service owns accounts and their owning agents (ADR-0035), so
// transaction-service does not decide ownership itself. It forwards the
// caller's own token, and account-service applies its rule: admins see every
// account, and an agent asking about another agent's account gets the same
// 404 as for a missing one (ADR-0064 decision 2).
package accounts

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// maxResponseBytes bounds how much of an answer is read. An agent's account
// list is the largest, at well under 1 MiB; the limit only guards against a
// misbehaving upstream.
const maxResponseBytes = 2 << 20

// ErrUnavailable means account-service gave no usable answer, so the caller
// cannot be checked and the read is refused with 502.
var ErrUnavailable = errors.New("account-service gave no usable answer")

// Refused is account-service's own refusal of the caller (401, 403 or 404),
// passed on to the caller unchanged.
type Refused struct {
	Status int
}

func (r Refused) Error() string {
	return fmt.Sprintf("account-service refused the caller with HTTP %d", r.Status)
}

type Directory interface {
	// CheckAccess returns nil when the caller may read the account, Refused
	// when account-service says they may not, and ErrUnavailable otherwise.
	CheckAccess(ctx context.Context, accountID, token string) error
	// OwnedAccountIDs returns the IDs of the accounts the caller owns, or
	// Refused or ErrUnavailable as CheckAccess does.
	OwnedAccountIDs(ctx context.Context, token string) ([]string, error)
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

func (d httpDirectory) CheckAccess(ctx context.Context, accountID, token string) error {
	response, err := d.get(ctx, "/api/accounts/"+url.PathEscape(accountID), token)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, maxResponseBytes))
	return outcome(response.StatusCode)
}

func (d httpDirectory) OwnedAccountIDs(ctx context.Context, token string) ([]string, error) {
	response, err := d.get(ctx, "/aggregates/accounts/ids", token)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if err := outcome(response.StatusCode); err != nil {
		return nil, err
	}
	var body struct {
		AccountIDs *[]string `json:"accountIds"`
	}
	if err := json.NewDecoder(io.LimitReader(response.Body, maxResponseBytes)).Decode(&body); err != nil || body.AccountIDs == nil {
		return nil, fmt.Errorf("%w: no account list", ErrUnavailable)
	}
	return *body.AccountIDs, nil
}

func (d httpDirectory) get(ctx context.Context, path, token string) (*http.Response, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, d.baseURL+path, nil)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	request.Header.Set("Authorization", "Bearer "+token)
	request.Header.Set("Accept", "application/json")
	response, err := d.client.Do(request)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	return response, nil
}

// outcome maps account-service's status: 2xx admits the caller, a refusal
// of the caller is passed on, and anything else is no answer.
func outcome(status int) error {
	switch {
	case status >= 200 && status < 300:
		return nil
	case status == http.StatusUnauthorized, status == http.StatusForbidden, status == http.StatusNotFound:
		return Refused{Status: status}
	default:
		return fmt.Errorf("%w: HTTP %d", ErrUnavailable, status)
	}
}
