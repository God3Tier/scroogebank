package client

import (
	"context"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestIsClientDuplicate(t *testing.T) {
	const id = "00000000-0000-0000-0000-000000000101"
	tests := map[string]struct {
		deleted bool
		email   string
		phone   string
		want    bool
	}{
		"detects an existing email":                 {email: "alice-101@example.test", phone: "+6560000999", want: true},
		"detects an email in another case":          {email: " ALICE-101@Example.test ", phone: "+6560000999", want: true},
		"detects an existing phone":                 {email: "new@example.test", phone: "+6560000101", want: true},
		"detects a phone with different formatting": {email: "new@example.test", phone: "+65 6000-0101", want: true},
		"returns false for new contact details":     {email: "new@example.test", phone: "+6560000999"},
		"ignores a deleted client's email":          {deleted: true, email: "alice-101@example.test", phone: "+6560000999"},
		"ignores a deleted client's phone":          {deleted: true, email: "new@example.test", phone: "+6560000101"},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			db, repo, _ := newTestRepository(t)
			seedClient(t, db, repo, id, "alice-101@example.test", "+6560000101")
			if test.deleted {
				markDeleted(t, db, id)
			}
			indexes, err := repo.contactIndexesFor(context.Background(), test.email, test.phone)
			require.NoError(t, err)
			assert.Equal(t, test.want, repo.isClientDuplicate(context.Background(), indexes))
		})
	}
}
