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
	ErrAlreadyVerified = errors.New("client is already verified")

	// Identity documents (ADR-0041)
	ErrDocumentNotFound    = errors.New("document not found")
	ErrDocumentTooLarge    = errors.New("document is larger than 5 MB")
	ErrUnsupportedDocument = errors.New("document must be a JPEG, PNG or PDF file")

	// Accounts live in account-service. These describe what that service
	// reported when asked whether a client can be deleted.
	ErrClientHasAccounts         = errors.New("client still has accounts")
	ErrAccountServiceUnavailable = errors.New("account service unavailable")
)
