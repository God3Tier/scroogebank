package client

import (
	clienterrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
	orm "client-profile-management-service/src/internal/repository/orm"
	"context"

	"github.com/aarondl/sqlboiler/v4/boil"
)

// UpdateClientInformation re-encrypts the personal fields with the client's
// existing data key and recomputes the email and phone blind indexes. Taking
// another client's email or phone is refused with ErrDuplicateClient, as on
// create.
func (r clientRepository) UpdateClientInformation(ctx context.Context, clientID string, client models.Client) error {
	existingClient, err := r.findClient(ctx, clientID)
	if err != nil {
		return err
	}
	key, err := r.protector.Key(ctx, existingClient.ClientID)
	if err != nil {
		return err
	}
	indexes, err := r.contactIndexesFor(ctx, client.Email, client.PhoneNumber)
	if err != nil {
		return err
	}
	if err := encryptInto(existingClient, key, client); err != nil {
		return err
	}
	existingClient.EmailHash = indexes.email
	existingClient.PhoneNumberHash = indexes.phone

	_, err = existingClient.Update(ctx, r.db, profileColumns)
	if isContactTaken(err) {
		return clienterrors.ErrDuplicateClient
	}
	return err
}

// profileColumns are the columns an update writes. Writing only these keeps a
// verification or a delete that commits between the read and the write
// (ADR-0036, ADR-0041).
var profileColumns = boil.Whitelist(
	orm.ClientColumns.FirstName, orm.ClientColumns.LastName, orm.ClientColumns.DateOfBirth,
	orm.ClientColumns.Gender, orm.ClientColumns.Email, orm.ClientColumns.EmailHash,
	orm.ClientColumns.PhoneNumber, orm.ClientColumns.PhoneNumberHash, orm.ClientColumns.Address,
	orm.ClientColumns.City, orm.ClientColumns.State, orm.ClientColumns.Country,
	orm.ClientColumns.PostalCode, orm.ClientColumns.UpdatedAt,
)
