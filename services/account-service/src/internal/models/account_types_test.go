package models

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestAccountTypeIsValid(t *testing.T) {
	tests := map[string]struct {
		accountType AccountType
		want        bool
	}{
		"checking":    {accountType: AccountTypeChecking, want: true},
		"savings":     {accountType: AccountTypeSavings, want: true},
		"credit":      {accountType: AccountTypeCredit, want: true},
		"loan":        {accountType: AccountTypeLoan, want: true},
		"investment":  {accountType: AccountTypeInvestment, want: true},
		"unsupported": {accountType: AccountType("UNSUPPORTED"), want: false},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			assert.Equal(t, test.want, test.accountType.IsValid())
		})
	}
}
