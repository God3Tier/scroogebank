package client

import (
	"strings"
	"testing"
	"time"

	clienterrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"

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

func TestValidateClientFormats(t *testing.T) {
	// 22 October 2026 in Singapore.
	now = func() time.Time { return time.Date(2026, 10, 21, 18, 0, 0, 0, time.UTC) }
	t.Cleanup(func() { now = time.Now })

	tests := map[string]struct {
		change func(*models.Client)
		ok     bool
	}{
		"accepts a name with a space and accents": {func(c *models.Client) { c.FirstName = "Mei Ling"; c.LastName = "Núñez" }, true},
		"rejects a one-letter name":               {func(c *models.Client) { c.FirstName = "A" }, false},
		"rejects digits in a name":                {func(c *models.Client) { c.LastName = "Smith2" }, false},
		"rejects punctuation in a name":           {func(c *models.Client) { c.LastName = "O'Brien" }, false},
		"accepts an 18th birthday today":          {func(c *models.Client) { c.DateOfBirth = date(2008, 10, 22) }, true},
		"rejects 18 tomorrow":                     {func(c *models.Client) { c.DateOfBirth = date(2008, 10, 23) }, false},
		"accepts age 100":                         {func(c *models.Client) { c.DateOfBirth = date(1926, 10, 22) }, true},
		"rejects age 101":                         {func(c *models.Client) { c.DateOfBirth = date(1925, 10, 22) }, false},
		"rejects a future date of birth":          {func(c *models.Client) { c.DateOfBirth = date(2027, 1, 1) }, false},
		"rejects an email without a domain dot":   {func(c *models.Client) { c.Email = "alice@example" }, false},
		"rejects an email with a space":           {func(c *models.Client) { c.Email = "alice smith@example.test" }, false},
		"accepts a phone with separators":         {func(c *models.Client) { c.PhoneNumber = "+65 9123-4567" }, true},
		"accepts 15 digits":                       {func(c *models.Client) { c.PhoneNumber = "123456789012345" }, true},
		"rejects 8 local digits":                  {func(c *models.Client) { c.PhoneNumber = "91234567" }, false},
		"rejects letters in a phone":              {func(c *models.Client) { c.PhoneNumber = "+65 9123 ABCD" }, false},
		"rejects a short address":                 {func(c *models.Client) { c.Address = "1 Rd" }, false},
		"rejects a one-letter city":               {func(c *models.Client) { c.City = "S" }, false},
		"rejects 5 digits for Singapore":          {func(c *models.Client) { c.PostalCode = "12345" }, false},
		"accepts Singapore written as SG.":        {func(c *models.Client) { c.Country = "S.G."; c.PostalCode = "018956" }, true},
		"rejects letters for Singapore":           {func(c *models.Client) { c.Country = "Republic of Singapore"; c.PostalCode = "AB1234" }, false},
		"accepts another country's format":        {func(c *models.Client) { c.Country = "United Kingdom"; c.PostalCode = "SW1A 1AA" }, true},
		"rejects a 3-character postal code":       {func(c *models.Client) { c.Country = "Malaysia"; c.PostalCode = "123" }, false},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			profile := validClient()
			test.change(&profile)
			err := validateClientProfile(profile, true)
			if test.ok {
				assert.NoError(t, err)
				return
			}
			assert.ErrorIs(t, err, clienterrors.ErrBadInput)
		})
	}
}

func date(year int, month time.Month, day int) time.Time {
	return time.Date(year, month, day, 0, 0, 0, 0, time.UTC)
}
