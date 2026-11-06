package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"producer/src/accounts"
	"producer/src/transaction"
)

const syncedAccount = "0b7c6f1e-4a0e-4f7c-9a52-6f1c3b2d8e11"

func TestRunUsesOnlyTheSyncedAccounts(t *testing.T) {
	destDir := t.TempDir()
	ids := filepath.Join(t.TempDir(), "account-ids")
	writeFile(t, ids, syncedAccount+"\n")

	run(destDir, accounts.Source{File: ids})

	for _, row := range published(t, destDir) {
		if row.AccountID != syncedAccount {
			t.Fatalf("transaction %s is on account %q, want only %q", row.TransactionID, row.AccountID, syncedAccount)
		}
	}
}

func TestRunUsesTheSyncedAccountsCurrency(t *testing.T) {
	destDir := t.TempDir()
	ids := filepath.Join(t.TempDir(), "account-ids")
	writeFile(t, ids, syncedAccount+":USD\n")

	run(destDir, accounts.Source{File: ids})

	for _, row := range published(t, destDir) {
		if row.Currency != "USD" && row.Currency != "Unsupported" {
			t.Fatalf("transaction %s is in %q, want the account's USD", row.TransactionID, row.Currency)
		}
	}
}

func TestRunPublishesNothingWithoutSyncedAccounts(t *testing.T) {
	destDir := t.TempDir()
	ids := filepath.Join(t.TempDir(), "account-ids")
	writeFile(t, ids, "")

	run(destDir, accounts.Source{File: ids})
	run(destDir, accounts.Source{File: filepath.Join(t.TempDir(), "missing")})

	if entries, _ := os.ReadDir(destDir); len(entries) != 0 {
		t.Fatalf("published %d files, want none", len(entries))
	}
}

func TestRunWithoutASyncedFileUsesExternalReferences(t *testing.T) {
	destDir := t.TempDir()

	run(destDir, accounts.Source{})

	for _, row := range published(t, destDir) {
		if !strings.HasPrefix(row.AccountID, "ACC-") {
			t.Fatalf("account %q, want a random ACC- reference", row.AccountID)
		}
	}
}

// published returns the rows of the one file a run published.
func published(t *testing.T, destDir string) []transaction.Transaction {
	t.Helper()
	entries, err := os.ReadDir(destDir)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || filepath.Ext(entries[0].Name()) != ".json" {
		t.Fatalf("published %v, want one .json file", entries)
	}
	content, err := os.ReadFile(filepath.Join(destDir, entries[0].Name()))
	if err != nil {
		t.Fatal(err)
	}
	var rows []transaction.Transaction
	if err := json.Unmarshal(content, &rows); err != nil {
		t.Fatalf("published file is not a JSON array: %v", err)
	}
	if len(rows) == 0 {
		t.Fatal("published file has no transactions")
	}
	return rows
}

func writeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestGetenvDurationFallsBackOnAnIntervalThatIsNotPositive(t *testing.T) {
	for _, v := range []string{"0", "-5", "abc"} {
		t.Setenv("INTERVAL_SECONDS", v)
		if got := getenvDuration("INTERVAL_SECONDS", 300); got != 300*time.Second {
			t.Fatalf("INTERVAL_SECONDS=%q gave %s, want the 300s fallback", v, got)
		}
	}
}

func TestGetenvDurationReadsSeconds(t *testing.T) {
	t.Setenv("INTERVAL_SECONDS", "60")
	if got := getenvDuration("INTERVAL_SECONDS", 300); got != time.Minute {
		t.Fatalf("got %s, want 1m0s", got)
	}
}
