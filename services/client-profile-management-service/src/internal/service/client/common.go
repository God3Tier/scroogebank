package client

import (
	clientErr "client-profile-management-service/src/internal/errors"

	"client-profile-management-service/src/internal/encryption"
	"client-profile-management-service/src/internal/models"
	"fmt"
	"regexp"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
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

	// Minimums and the age range from the project brief's Appendix 2 validations.
	minNameLength       = 2
	minAddressLength    = 5
	minPlaceLength      = 2
	minPostalCodeLength = 4
	minAge              = 18
	maxAge              = 100
)

var (
	emailPattern = regexp.MustCompile(`^[^\s@]+@[^\s@]+\.[^\s@]+$`)
	// The brief's format: an optional + and 10 to 15 digits, after the
	// separators NormalizePhone removes (spaces, hyphens, dots, brackets).
	phonePattern           = regexp.MustCompile(`^\+?[0-9]{10,15}$`)
	singaporePostalPattern = regexp.MustCompile(`^[0-9]{6}$`)
	postalPattern          = regexp.MustCompile(`^[A-Za-z0-9 -]+$`)

	// Ages are counted in Singapore time; tests replace now.
	singapore = time.FixedZone("SGT", 8*60*60)
	now       = time.Now
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
		{name: "assigned_agent_id", value: strings.TrimSpace(client.AssignedAgentID), max: 0},
	}

	for _, field := range trimmedValues {
		if field.name == "assigned_agent_id" && !requireVerificationStatus {
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

	if err := validateFormats(client); err != nil {
		return err
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

// validateFormats applies the brief's business rules once every field is
// present: names of letters and spaces, an adult's date of birth, the email
// and phone formats, and a Singapore postal code for a Singapore address.
func validateFormats(client models.Client) error {
	for _, name := range []struct{ field, value string }{
		{"first_name", strings.TrimSpace(client.FirstName)},
		{"last_name", strings.TrimSpace(client.LastName)},
	} {
		if utf8.RuneCountInString(name.value) < minNameLength {
			return fmt.Errorf("%w: %s must be at least %d characters", clientErr.ErrBadInput, name.field, minNameLength)
		}
		if !lettersAndSpaces(name.value) {
			return fmt.Errorf("%w: %s must contain only letters and spaces", clientErr.ErrBadInput, name.field)
		}
	}

	if age := ageOn(client.DateOfBirth, now().In(singapore)); age < minAge || age > maxAge {
		return fmt.Errorf("%w: date_of_birth must give an age between %d and %d", clientErr.ErrBadInput, minAge, maxAge)
	}

	if !emailPattern.MatchString(strings.TrimSpace(client.Email)) {
		return fmt.Errorf("%w: email must be a valid email address", clientErr.ErrBadInput)
	}

	if !phonePattern.MatchString(encryption.NormalizePhone(client.PhoneNumber)) {
		return fmt.Errorf("%w: phone_number must be 10 to 15 digits with an optional leading +, for example +6591234567", clientErr.ErrBadInput)
	}

	if utf8.RuneCountInString(strings.TrimSpace(client.Address)) < minAddressLength {
		return fmt.Errorf("%w: address must be at least %d characters", clientErr.ErrBadInput, minAddressLength)
	}
	for _, place := range []struct{ field, value string }{
		{"city", client.City}, {"state", client.State}, {"country", client.Country},
	} {
		if utf8.RuneCountInString(strings.TrimSpace(place.value)) < minPlaceLength {
			return fmt.Errorf("%w: %s must be at least %d characters", clientErr.ErrBadInput, place.field, minPlaceLength)
		}
	}

	postalCode := strings.TrimSpace(client.PostalCode)
	if isSingapore(client.Country) {
		if !singaporePostalPattern.MatchString(postalCode) {
			return fmt.Errorf("%w: postal_code must be 6 digits for a Singapore address", clientErr.ErrBadInput)
		}
	} else if len(postalCode) < minPostalCodeLength || !postalPattern.MatchString(postalCode) {
		return fmt.Errorf("%w: postal_code must be %d to %d letters, digits, spaces or hyphens", clientErr.ErrBadInput, minPostalCodeLength, maxPostalCodeLength)
	}
	return nil
}

func lettersAndSpaces(value string) bool {
	for _, r := range value {
		if !unicode.IsLetter(r) && r != ' ' {
			return false
		}
	}
	return true
}

// ageOn is the age in whole years on the given day; a birthday after today
// gives a negative age.
func ageOn(birth time.Time, today time.Time) int {
	age := today.Year() - birth.Year()
	if today.Month() < birth.Month() || (today.Month() == birth.Month() && today.Day() < birth.Day()) {
		age--
	}
	return age
}

// isSingapore reads the free-text country as engagement-service does:
// punctuation ignored, any case, SG, SGP, Singapore or Republic of Singapore.
func isSingapore(country string) bool {
	key := strings.Map(func(r rune) rune {
		if unicode.IsLetter(r) || unicode.IsSpace(r) {
			return unicode.ToUpper(r)
		}
		return -1
	}, country)
	switch strings.Join(strings.Fields(key), " ") {
	case "SG", "SGP", "SINGAPORE", "REPUBLIC OF SINGAPORE":
		return true
	}
	return false
}
