package client

import (
	"context"
	"errors"
	"strings"
	"testing"

	"client-profile-management-service/src/internal/encryption/encryptiontest"
	clienterrors "client-profile-management-service/src/internal/errors"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestCreateClientStoresEncryptedFieldsAndBlindIndexes(t *testing.T) {
	db, repo, protector := newTestRepository(t)
	profile := validRepositoryClient()
	clearContact(t, db, profile.ClientID.String(), profile.Email, profile.PhoneNumber)
	t.Cleanup(func() { _, _ = db.Exec(`DELETE FROM clients WHERE client_id = $1`, profile.ClientID.String()) })

	require.NoError(t, repo.CreateClient(context.Background(), profile))

	var firstName, dateOfBirth, email, emailHash, phone, phoneHash, status string
	require.NoError(t, db.QueryRow(`SELECT first_name, date_of_birth, email, email_hash, phone_number, phone_number_hash, verification_status
		FROM clients WHERE client_id = $1`, profile.ClientID.String()).
		Scan(&firstName, &dateOfBirth, &email, &emailHash, &phone, &phoneHash, &status))
	assert.NotContains(t, firstName, "Alice")
	assert.NotContains(t, dateOfBirth, "1990")
	assert.NotContains(t, email, "example.test")
	assert.NotContains(t, phone, "6560000199")
	assert.Equal(t, encryptiontest.Index("alice-new@example.test"), emailHash)
	assert.Equal(t, encryptiontest.Index("+6560000199"), phoneHash)
	assert.Equal(t, "UNVERIFIED", status, "verification status stays plaintext")
	assert.True(t, protector.HasKey(profile.ClientID.String()))

	got, err := repo.GetClientProfile(context.Background(), profile.ClientID.String())
	require.NoError(t, err)
	assert.Equal(t, profile.FirstName, got.FirstName)
	assert.Equal(t, profile.DateOfBirth, got.DateOfBirth)
	assert.Equal(t, profile.PostalCode, got.PostalCode)
}

func TestCreateClientRejectsADuplicateBeforeCreatingAKey(t *testing.T) {
	db, repo, protector := newTestRepository(t)
	seedClient(t, db, repo, "00000000-0000-0000-0000-000000000102", "duplicate@example.test", "+6560000102")
	profile := validRepositoryClient()
	profile.Email = "Duplicate@Example.test"

	err := repo.CreateClient(context.Background(), profile)

	assert.ErrorIs(t, err, clienterrors.ErrDuplicateClient)
	assert.False(t, protector.HasKey(profile.ClientID.String()))
}

func TestCreateClientReusesADeletedClientsEmailAndPhone(t *testing.T) {
	db, repo, _ := newTestRepository(t)
	seedClient(t, db, repo, "00000000-0000-0000-0000-000000000103", "deleted-103@example.test", "+6560000103")
	markDeleted(t, db, "00000000-0000-0000-0000-000000000103")
	profile := validRepositoryClient()
	profile.Email, profile.PhoneNumber = "deleted-103@example.test", "+6560000103"
	t.Cleanup(func() { _, _ = db.Exec(`DELETE FROM clients WHERE client_id = $1`, profile.ClientID.String()) })

	assert.NoError(t, repo.CreateClient(context.Background(), profile))
}

func TestCreateClientStoresNothingWhenKeysAreUnavailable(t *testing.T) {
	db, repo, protector := newTestRepository(t)
	protector.Err = errors.New("kms unavailable")
	profile := validRepositoryClient()

	assert.Error(t, repo.CreateClient(context.Background(), profile))

	var count int
	require.NoError(t, db.QueryRow(`SELECT count(*) FROM clients WHERE client_id = $1`, profile.ClientID.String()).Scan(&count))
	assert.Zero(t, count)
}

func TestCreateClientRemovesTheNewKeyWhenTheInsertFails(t *testing.T) {
	db, repo, protector := newTestRepository(t)
	loadTestFixture(t, db, "client_empty.sql")
	profile := validRepositoryClient()
	// Longer than the column allows, so the insert fails after the key exists.
	profile.AssignedAgentID = strings.Repeat("a", 129)

	assert.Error(t, repo.CreateClient(context.Background(), profile))
	assert.False(t, protector.HasKey(profile.ClientID.String()))
}
