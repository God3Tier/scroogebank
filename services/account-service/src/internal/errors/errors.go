package errors

import "errors"

var (
	ErrBadInput             = errors.New("bad input")
	ErrInvalidData          = errors.New("invalid data")
	ErrMissingRequiredField = errors.New("missing required field")
	ErrMethodNotImplemented = errors.New("method not implemented")
	ErrNotFound             = errors.New("not found")

	// Client errors. Clients live in client-profile-management-service; these
	// describe what that service reported when asked about one.
	ErrInvalidClientID          = errors.New("invalid client id")
	ErrClientNotFound           = errors.New("client not found")
	ErrClientServiceUnavailable = errors.New("client service unavailable")

	// Account errors
	ErrInvalidBranchID      = errors.New("invalid branch id")
	ErrInvalidAccountType   = errors.New("invalid account type")
	ErrInvalidAccountID     = errors.New("invalid account number")
	ErrInvalidAccountStatus = errors.New("invalid account status")
	ErrInvalidCurrency      = errors.New("invalid currency")
	ErrDuplicateAccount     = errors.New("duplicate account")
)
