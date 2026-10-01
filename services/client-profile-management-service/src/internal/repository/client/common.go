package client

import (
	orm "client-profile-management-service/src/internal/repository/orm"
	"context"

	"github.com/aarondl/sqlboiler/v4/queries/qm"
)

func (r clientRepository) isClientDuplicate(ctx context.Context, clientEmail string, clientPhone string) bool {
	exists, err := orm.Clients(
		qm.Select(orm.ClientColumns.ClientID),
		qm.Where("email = ?", clientEmail),
		qm.Or2(qm.Where("phone_number = ?", clientPhone)),
	).Exists(ctx, r.db)
	if err != nil {
		return false
	}

	return exists
}
