package transaction

import (
	"regexp"
	"testing"
	"time"

	"producer/src/accounts"
)

func TestGenerateHistoryIncludesThreeSignalsAndHealthyControl(t *testing.T) {
	accountsList := []accounts.Account{
		{ID: "case-a-recurring-interruption", Currency: "SGD"},
		{ID: "case-b-activity-decline", Currency: "SGD"},
		{ID: "case-c-sustained-outflow", Currency: "SGD"},
		{ID: "case-d-healthy-control", Currency: "SGD"},
	}
	anchor := time.Date(2026, 10, 27, 12, 0, 0, 0, time.UTC)
	rows := GenerateHistory(accountsList, anchor)
	if len(rows) == 0 {
		t.Fatal("GenerateHistory() returned no rows")
	}

	credits := make([]int, len(accountsList))
	activityBefore := make([]int, len(accountsList))
	activityAfter := make([]int, len(accountsList))
	spendBefore := make([]float64, len(accountsList))
	spendAfter := make([]float64, len(accountsList))
	var recurringFingerprint string
	fingerprintFormat := regexp.MustCompile(`^synthetic-[a-z0-9]+(-[a-z0-9]+)*-[a-f0-9]{12}$`)
	start := time.Date(2026, 4, 1, 0, 0, 0, 0, time.UTC)
	for _, row := range rows {
		index := -1
		for i, account := range accountsList {
			if row.AccountID == account.ID {
				index = i
				break
			}
		}
		if index < 0 {
			t.Fatalf("unexpected account %q", row.AccountID)
		}
		if row.TransactionType == "Deposit" {
			credits[index]++
			recurringFingerprint = row.SourceFingerprint
			if !fingerprintFormat.MatchString(row.SourceFingerprint) || len(row.SourceFingerprint) > 64 {
				t.Fatalf("invalid recurring fingerprint %q", row.SourceFingerprint)
			}
			continue
		}
		if row.ActivityKind != "CustomerInitiated" {
			t.Fatalf("withdrawal activity kind = %q", row.ActivityKind)
		}
		if row.TransferDate.Before(start.AddDate(0, 0, 16*7)) {
			activityBefore[index]++
			spendBefore[index] += row.Amount
		} else {
			activityAfter[index]++
			spendAfter[index] += row.Amount
		}
	}
	if recurringFingerprint == "" {
		t.Fatal("recurring credit fingerprint is empty")
	}
	if credits[0] != 4 || credits[1] != 6 || credits[2] != 6 || credits[3] != 6 {
		t.Fatalf("monthly credit counts = %v, want interruption then three complete streams", credits)
	}
	if activityAfter[1] >= activityBefore[1] {
		t.Fatalf("activity decline case did not decline: before=%d after=%d", activityBefore[1], activityAfter[1])
	}
	if spendAfter[2] <= spendBefore[2] {
		t.Fatalf("net outflow case did not increase outflows: before=%.2f after=%.2f", spendBefore[2], spendAfter[2])
	}
	if activityAfter[3] == 0 || activityAfter[3]*16 != activityBefore[3]*10 {
		t.Fatalf("healthy control activity is not stable: before=%d after=%d", activityBefore[3], activityAfter[3])
	}
}

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
// default currency.
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
