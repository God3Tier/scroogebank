package transaction

import (
	"testing"

	"producer/src/accounts"
)

var (
	sgdAccount = accounts.Account{ID: "0b7c6f1e-4a0e-4f7c-9a52-6f1c3b2d8e11", Currency: "SGD"}
	usdAccount = accounts.Account{ID: "6a1d2c3b-5e4f-4a7b-8c9d-0e1f2a3b4c5d", Currency: "USD"}
)

// A valid transaction is in its account's currency, so an account's
// transactions never show another one.
func TestGenerateUsesTheAccountsCurrency(t *testing.T) {
	list := []accounts.Account{sgdAccount, usdAccount}
	for range 1000 {
		row := Generate(list)
		for _, account := range list {
			if row.AccountID == account.ID && row.Currency != account.Currency {
				t.Fatalf("transaction on %s is in %q, want the account's %q", account.ID, row.Currency, account.Currency)
			}
		}
	}
}

// A random external reference has no CRM account, so it is in the
// deployment's currency.
func TestGenerateWithoutAccountsUsesSGD(t *testing.T) {
	for range 1000 {
		if got := Generate(nil).Currency; got != "SGD" {
			t.Fatalf("Generate() currency = %q, want SGD", got)
		}
	}
}

// Only the rows made unsupported on purpose leave their account's currency.
func TestGenerateRandomSetUsesTheAccountsCurrencyOrTheUnsupportedFault(t *testing.T) {
	for range 1000 {
		for _, row := range GenerateRandomSet(usdAccount) {
			if row.Currency != "USD" && row.Currency != "Unsupported" {
				t.Fatalf("GenerateRandomSet() currency = %q, want USD or the Unsupported fault", row.Currency)
			}
		}
	}
}
