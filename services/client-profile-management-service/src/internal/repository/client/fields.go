package client

import (
	"context"
	"fmt"
	"time"

	"client-profile-management-service/src/internal/encryption"
	"client-profile-management-service/src/internal/models"
	orm "client-profile-management-service/src/internal/repository/orm"
)

// dateOfBirthLayout is how a date of birth is written before it is encrypted.
const dateOfBirthLayout = "2006-01-02"

// contactIndexes are the blind indexes that keep email and phone unique.
type contactIndexes struct {
	email string
	phone string
}

// contactIndexesFor computes the blind indexes of the normalised email and
// phone (ADR-0030 decision 6).
func (r clientRepository) contactIndexesFor(ctx context.Context, email, phone string) (contactIndexes, error) {
	emailHash, err := r.protector.BlindIndex(ctx, encryption.NormalizeEmail(email))
	if err != nil {
		return contactIndexes{}, fmt.Errorf("index email: %w", err)
	}
	phoneHash, err := r.protector.BlindIndex(ctx, encryption.NormalizePhone(phone))
	if err != nil {
		return contactIndexes{}, fmt.Errorf("index phone number: %w", err)
	}
	return contactIndexes{email: emailHash, phone: phoneHash}, nil
}

// encryptInto writes the client's personal fields into row, encrypted with
// the client's data key.
func encryptInto(row *orm.Client, key encryption.DataKey, client models.Client) error {
	fields := []struct {
		name  string
		value string
		into  *string
	}{
		{models.FieldFirstName, client.FirstName, &row.FirstName},
		{models.FieldLastName, client.LastName, &row.LastName},
		{models.FieldDateOfBirth, client.DateOfBirth.Format(dateOfBirthLayout), &row.DateOfBirth},
		{models.FieldGender, client.Gender, &row.Gender},
		{models.FieldEmail, client.Email, &row.Email},
		{models.FieldPhoneNumber, client.PhoneNumber, &row.PhoneNumber},
		{models.FieldAddress, client.Address, &row.Address},
		{models.FieldCity, client.City, &row.City},
		{models.FieldState, client.State, &row.State},
		{models.FieldCountry, client.Country, &row.Country},
		{models.FieldPostalCode, client.PostalCode, &row.PostalCode},
	}
	for _, field := range fields {
		ciphertext, err := key.Encrypt(field.name, field.value)
		if err != nil {
			return err
		}
		*field.into = ciphertext
	}
	return nil
}

// decryptFrom reads the client's personal fields from row.
func decryptFrom(row *orm.Client, key encryption.DataKey) (models.Client, error) {
	var client models.Client
	var dateOfBirth string
	fields := []struct {
		name       string
		ciphertext string
		into       *string
	}{
		{models.FieldFirstName, row.FirstName, &client.FirstName},
		{models.FieldLastName, row.LastName, &client.LastName},
		{models.FieldDateOfBirth, row.DateOfBirth, &dateOfBirth},
		{models.FieldGender, row.Gender, &client.Gender},
		{models.FieldEmail, row.Email, &client.Email},
		{models.FieldPhoneNumber, row.PhoneNumber, &client.PhoneNumber},
		{models.FieldAddress, row.Address, &client.Address},
		{models.FieldCity, row.City, &client.City},
		{models.FieldState, row.State, &client.State},
		{models.FieldCountry, row.Country, &client.Country},
		{models.FieldPostalCode, row.PostalCode, &client.PostalCode},
	}
	for _, field := range fields {
		plaintext, err := key.Decrypt(field.name, field.ciphertext)
		if err != nil {
			return models.Client{}, err
		}
		*field.into = plaintext
	}
	parsed, err := time.Parse(dateOfBirthLayout, dateOfBirth)
	if err != nil {
		return models.Client{}, fmt.Errorf("parse date_of_birth: %w", err)
	}
	client.DateOfBirth = parsed
	return client, nil
}
