package models

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestAccountStatusIsValid(t *testing.T) {
	tests := map[string]struct {
		status AccountStatus
		want   bool
	}{
		"active":      {status: AccountStatusActive, want: true},
		"inactive":    {status: AccountStatusInactive, want: true},
		"pending":     {status: AccountStatusPending, want: true},
		"closed":      {status: AccountStatusClosed, want: true},
		"unsupported": {status: AccountStatus("UNSUPPORTED"), want: false},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			assert.Equal(t, test.want, test.status.IsValid())
		})
	}
}
