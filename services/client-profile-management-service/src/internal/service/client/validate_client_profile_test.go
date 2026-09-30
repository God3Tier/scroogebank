package client

import (
	"strings"
	"testing"
	"time"

	clienterrors "client-profile-management-service/src/internal/errors"

	"github.com/stretchr/testify/assert"
)

func TestValidateClientProfile(t *testing.T) {
	tests := map[string]struct {
		client  func() error
		wantErr error
	}{
		"accepts complete profile": {
			client: func() error { return validateClientProfile(validClient(), true) },
		},
		"rejects missing required field": {
			client: func() error {
				profile := validClient()
				profile.FirstName = "  "
				return validateClientProfile(profile, true)
			},
			wantErr: clienterrors.ErrBadInput,
		},
		"rejects field exceeding maximum": {
			client: func() error {
				profile := validClient()
				profile.FirstName = strings.Repeat("a", maxFirstNameLength+1)
				return validateClientProfile(profile, true)
			},
			wantErr: clienterrors.ErrBadInput,
		},
		"rejects missing date of birth": {
			client: func() error {
				profile := validClient()
				profile.DateOfBirth = time.Time{}
				return validateClientProfile(profile, true)
			},
			wantErr: clienterrors.ErrBadInput,
		},
		"rejects oversized verification status": {
			client: func() error {
				profile := validClient()
				profile.VerificationStatus = strings.Repeat("a", maxVerificationLength+1)
				return validateClientProfile(profile, true)
			},
			wantErr: clienterrors.ErrBadInput,
		},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			err := test.client()
			if test.wantErr != nil {
				assert.ErrorIs(t, err, test.wantErr)
				return
			}
			assert.NoError(t, err)
		})
	}
}
