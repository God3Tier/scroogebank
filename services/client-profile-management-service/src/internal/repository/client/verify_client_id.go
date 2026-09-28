package client

import (
	orm "client-profile-management-service/src/internal/repository/orm"
	"context"

	"github.com/aarondl/sqlboiler/v4/boil"
)

// This function assumes the verification prerequisites is fulfilled & only serves to reflect this status in the DB.
func (r clientRepository) VerifyClientIdentity(ctx context.Context, clientID string) error {
	client, err := orm.FindClient(ctx, r.db, clientID)
	if err != nil {
		return err
	}

	client.VerificationStatus = "verified"
	_, err = client.Update(ctx, r.db, boil.Infer())
	return err
}

func (r clientRepository) DeleteClient(ctx context.Context, clientID string) error {
	client, err := orm.FindClient(ctx, r.db, clientID)
	if err != nil {
		return err
	}

	_, err = client.Delete(ctx, r.db)
	return err
}
