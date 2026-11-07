// Package users asks user-service who a CRM user is, so that clients are only
// ever reassigned to an enabled agent (ADR-0060).
package users

import (
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

// maxUserResponseBytes bounds how much of user-service's answer is read; a
// user is a handful of fields.
const maxUserResponseBytes = 64 << 10

// User is what a reassignment needs to know about a CRM user.
type User struct {
	// Subject is the user's Cognito sub, the ID clients.assigned_agent_id holds.
	Subject string
	Role    string
	Enabled bool
}

type Directory interface {
	// Lookup returns the user with user-service's ID userID. It forwards the
	// caller's Authorization header: user-service answers admins only.
	// A missing user is ErrUserNotFound; anything else that is not an answer
	// is ErrUserServiceUnavailable.
	Lookup(ctx context.Context, userID, authorization string) (User, error)
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

func (d httpDirectory) Lookup(ctx context.Context, userID, authorization string) (User, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, d.baseURL+"/api/users/"+url.PathEscape(userID), nil)
	if err != nil {
		return User{}, fmt.Errorf("%w: build request: %v", clienterrors.ErrUserServiceUnavailable, err)
	}
	req.Header.Set("Authorization", authorization)
	req.Header.Set("Accept", "application/json")

	res, err := d.client.Do(req)
	if err != nil {
		return User{}, fmt.Errorf("%w: %v", clienterrors.ErrUserServiceUnavailable, err)
	}
	defer res.Body.Close()

	switch res.StatusCode {
	case http.StatusOK:
	case http.StatusNotFound:
		return User{}, clienterrors.ErrUserNotFound
	default:
		// Nothing moves on an answer that is not a user.
		return User{}, fmt.Errorf("%w: user service returned %d", clienterrors.ErrUserServiceUnavailable, res.StatusCode)
	}
	var body struct {
		IdpSub  string `json:"idpSub"`
		Role    string `json:"role"`
		Enabled bool   `json:"enabled"`
	}
	if err := json.NewDecoder(io.LimitReader(res.Body, maxUserResponseBytes)).Decode(&body); err != nil {
		return User{}, fmt.Errorf("%w: unreadable user: %v", clienterrors.ErrUserServiceUnavailable, err)
	}
	subject := strings.TrimSpace(body.IdpSub)
	if subject == "" {
		return User{}, fmt.Errorf("%w: user has no Cognito sub", clienterrors.ErrUserServiceUnavailable)
	}
	return User{Subject: subject, Role: strings.ToUpper(strings.TrimSpace(body.Role)), Enabled: body.Enabled}, nil
}
