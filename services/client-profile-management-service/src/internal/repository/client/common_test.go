package client

import (
	"context"
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestIsClientDuplicate(t *testing.T) {
	tests := map[string]struct {
		fixture string
		email   string
		phone   string
		want    bool
	}{
		"detects an existing email":             {fixture: "client_lookup.sql", email: "alice-101@example.test", phone: "+6560000999", want: true},
		"detects an existing phone":             {fixture: "client_lookup.sql", email: "new@example.test", phone: "+6560000101", want: true},
		"returns false for new contact details": {fixture: "client_lookup.sql", email: "new@example.test", phone: "+6560000999"},
		"ignores a deleted client's email":      {fixture: "client_deleted.sql", email: "deleted-103@example.test", phone: "+6560000999"},
		"ignores a deleted client's phone":      {fixture: "client_deleted.sql", email: "new@example.test", phone: "+6560000103"},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			db := openTestDB(t)
			loadTestFixture(t, db, test.fixture)
			got := (&clientRepository{db: db}).isClientDuplicate(context.Background(), test.email, test.phone)
			assert.Equal(t, test.want, got)
		})
	}
}
