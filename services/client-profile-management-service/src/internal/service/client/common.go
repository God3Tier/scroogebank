package client

import (
	clientErr "client-profile-management-service/src/internal/errors"

	"client-profile-management-service/src/internal/models"
	"fmt"
	"strings"
)

const (
	defaultVerificationStatus = "UNVERIFIED"
	maxFirstNameLength        = 50
	maxLastNameLength         = 50
	maxGenderLength           = 20
	maxEmailLength            = 255
	maxPhoneLength            = 20
	maxAddressLength          = 100
	maxCityLength             = 50
	maxStateLength            = 50
	maxCountryLength          = 50
	maxPostalCodeLength       = 10
	maxVerificationLength     = 20
)

func validateClientProfile(client models.Client, requireVerificationStatus bool) error {
	// The field 'max: 0' represents no enforced maximum length
	trimmedValues := []struct {
		name  string
		value string
		max   int
	}{
		{name: "client_id", value: strings.TrimSpace(client.ClientID.String()), max: 0},
		{name: "first_name", value: strings.TrimSpace(client.FirstName), max: maxFirstNameLength},
		{name: "last_name", value: strings.TrimSpace(client.LastName), max: maxLastNameLength},
		{name: "gender", value: strings.TrimSpace(client.Gender), max: maxGenderLength},
		{name: "email", value: strings.TrimSpace(client.Email), max: maxEmailLength},
		{name: "phone_number", value: strings.TrimSpace(client.PhoneNumber), max: maxPhoneLength},
		{name: "address", value: strings.TrimSpace(client.Address), max: maxAddressLength},
		{name: "city", value: strings.TrimSpace(client.City), max: maxCityLength},
		{name: "state", value: strings.TrimSpace(client.State), max: maxStateLength},
		{name: "country", value: strings.TrimSpace(client.Country), max: maxCountryLength},
		{name: "postal_code", value: strings.TrimSpace(client.PostalCode), max: maxPostalCodeLength},
		{name: "created_by_agent_id", value: strings.TrimSpace(client.CreatedByAgentID), max: 0},
	}

	for _, field := range trimmedValues {
		if field.name == "created_by_agent_id" && !requireVerificationStatus {
			continue
		}
		if field.value == "" {
			return fmt.Errorf("%w: %s is required; %w", clientErr.ErrBadInput, field.name, clientErr.ErrMissingRequiredField)
		}
		if field.max > 0 && len(field.value) > field.max {
			return fmt.Errorf("%w: %s exceeds max length %d", clientErr.ErrBadInput, field.name, field.max)
		}
	}

	if client.DateOfBirth.IsZero() {
		return fmt.Errorf("%w: date_of_birth is required; %w", clientErr.ErrBadInput, clientErr.ErrMissingRequiredField)
	}

	if requireVerificationStatus {
		verificationStatus := strings.TrimSpace(client.VerificationStatus)
		if verificationStatus == "" {
			client.VerificationStatus = defaultVerificationStatus
		} else if len(verificationStatus) > maxVerificationLength {
			return fmt.Errorf("%w: verification_status exceeds max length %d", clientErr.ErrBadInput, maxVerificationLength)
		}
	}

	return nil
}
