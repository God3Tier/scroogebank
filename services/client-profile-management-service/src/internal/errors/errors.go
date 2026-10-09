package errors

import "errors"

var (
	ErrBadInput             = errors.New("bad input")
	ErrInvalidData          = errors.New("invalid data")
	ErrMissingRequiredField = errors.New("missing required field")
	ErrMethodNotImplemented = errors.New("method not implemented")
	ErrNotFound             = errors.New("not found")
	ErrForbidden            = errors.New("forbidden")

	// Client errors
	ErrInvalidClientID = errors.New("invalid client id")
	ErrDuplicateClient = errors.New("duplicate client")
	ErrClientNotFound  = errors.New("client not found")

	// Accounts live in account-service. These describe what that service
	// reported when asked whether a client can be deleted.
	ErrClientHasAccounts         = errors.New("client still has accounts")
	ErrAccountServiceUnavailable = errors.New("account service unavailable")
)
