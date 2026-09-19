package client

import "errors"

var (
	ErrBadInput             = errors.New("bad input")
	ErrMissingRequiredField = errors.New("missing required field")
	ErrInvalidClientID      = errors.New("invalid client id")
	ErrDuplicateClient      = errors.New("duplicate client")
	ErrClientNotFound       = errors.New("client not found")
)
