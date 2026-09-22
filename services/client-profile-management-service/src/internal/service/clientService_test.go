package service

import (
	"errors"
	"strings"
	"testing"
	"time"
	"uuid"

	clienterrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
)

func TestValidateClientProfileDefaultsVerificationStatus(t *testing.T) {
	c, err := uuid.Parse("123e4567-e89b-12d3-a456-426614174000")
	if err != nil {
		t.Fatalf("uuid.Parse() returned unexpected error: %v", err)
	}
	client := models.Client{
		ClientID:         c,
		FirstName:        "Alice",
		LastName:         "Smith",
		DateOfBirth:      time.Date(1990, 5, 10, 0, 0, 0, 0, time.UTC),
		Gender:           "female",
		Email:            "alice@example.com",
		PhoneNumber:      "+1234567890",
		Address:          "1 Main Street",
		City:             "Seattle",
		State:            "WA",
		Country:          "USA",
		PostalCode:       "98101",
		CreatedByAgentID: "123e4567-e89b-12d3-a456-426614174001",
	}

	if err = validateClientProfile(&client, true); err != nil {
		t.Fatalf("validateClientProfile() returned unexpected error: %v", err)
	}

	if client.VerificationStatus != defaultVerificationStatus {
		t.Fatalf("VerificationStatus = %q, want %q", client.VerificationStatus, defaultVerificationStatus)
	}
}

func TestValidateClientProfileRejectsMissingRequiredFields(t *testing.T) {
	c, err := uuid.Parse("123e4567-e89b-12d3-a456-426614174000")
	if err != nil {
		t.Fatalf("uuid.Parse() returned unexpected error: %v", err)
	}
	client := models.Client{
		ClientID:         c,
		LastName:         "Smith",
		DateOfBirth:      time.Date(1990, 5, 10, 0, 0, 0, 0, time.UTC),
		Gender:           "female",
		Email:            "alice@example.com",
		PhoneNumber:      "+1234567890",
		Address:          "1 Main Street",
		City:             "Seattle",
		State:            "WA",
		Country:          "USA",
		PostalCode:       "98101",
		CreatedByAgentID: "123e4567-e89b-12d3-a456-426614174001",
	}

	err = validateClientProfile(&client, true)
	if !errors.Is(err, clienterrors.ErrBadInput) {
		t.Fatalf("validateClientProfile() error = %v, want ErrBadInput", err)
	}
}

func TestValidateClientProfileRejectsOversizedField(t *testing.T) {
	c, err := uuid.Parse("123e4567-e89b-12d3-a456-426614174000")
	if err != nil {
		t.Fatalf("uuid.Parse() returned unexpected error: %v", err)
	}
	client := models.Client{
		ClientID:         c,
		FirstName:        strings.Repeat("a", maxFirstNameLength+1),
		LastName:         "Smith",
		DateOfBirth:      time.Date(1990, 5, 10, 0, 0, 0, 0, time.UTC),
		Gender:           "female",
		Email:            "alice@example.com",
		PhoneNumber:      "+1234567890",
		Address:          "1 Main Street",
		City:             "Seattle",
		State:            "WA",
		Country:          "USA",
		PostalCode:       "98101",
		CreatedByAgentID: "123e4567-e89b-12d3-a456-426614174001",
	}

	err = validateClientProfile(&client, true)
	if !errors.Is(err, clienterrors.ErrBadInput) {
		t.Fatalf("validateClientProfile() error = %v, want ErrBadInput", err)
	}
}
