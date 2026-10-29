package errors

import "errors"

var (
	ErrBadInput             = errors.New("bad input")
	ErrInvalidData          = errors.New("invalid data")
	ErrMissingRequiredField = errors.New("missing required field")
	ErrMethodNotImplemented = errors.New("method not implemented")
	ErrNotFound             = errors.New("not found")
	ErrForbidden            = errors.New("forbidden")

	// Client errors. Clients live in client-profile-management-service; these
	// describe what that service reported when asked about one.
	ErrInvalidClientID          = errors.New("invalid client id")
	ErrClientNotFound           = errors.New("client not found")
	ErrClientServiceUnavailable = errors.New("client service unavailable")
	// ErrClientNotVerified refuses what an agent may do only for a verified
	// client: open an account that is not pending, or change an account's
	// status (ADR-0050). Each endpoint says which in its response.
	ErrClientNotVerified = errors.New("client is not verified")

	// Account errors
	ErrInvalidBranchID      = errors.New("invalid branch id")
	ErrInvalidAccountType   = errors.New("invalid account type")
	ErrInvalidAccountID     = errors.New("invalid account number")
	ErrInvalidAccountStatus = errors.New("invalid account status")
	ErrInvalidCurrency      = errors.New("invalid currency")
	ErrDuplicateAccount     = errors.New("duplicate account")
)
