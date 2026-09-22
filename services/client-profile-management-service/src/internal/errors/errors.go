package errors

import "errors"

var (
	ErrBadInput             = errors.New("bad input")
	ErrInvalidData			= errors.New("invalid data")
	ErrMissingRequiredField = errors.New("missing required field")
	ErrMethodNotImplemented = errors.New("method not implemented")
	ErrNotFound             = errors.New("not found")

	// Client errors
	ErrInvalidClientID      = errors.New("invalid client id")
	ErrDuplicateClient      = errors.New("duplicate client")
	ErrClientNotFound       = errors.New("client not found")

	// Account errors
	ErrInvalidBranchID      = errors.New("invalid branch id")
	ErrInvalidAccountType   = errors.New("invalid account type")
	ErrInvalidAccountID = errors.New("invalid account number")
	ErrInvalidAccountStatus = errors.New("invalid account status")
	ErrInvalidCurrency      = errors.New("invalid currency")
	ErrDuplicateAccount     = errors.New("duplicate account")
)
