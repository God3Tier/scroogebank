package accounts

import (
	"os"
	"path/filepath"
	"slices"
	"testing"
)

const (
	first  = "0b7c6f1e-4a0e-4f7c-9a52-6f1c3b2d8e11"
	second = "6a1d2c3b-5e4f-4a7b-8c9d-0e1f2a3b4c5d"
)

func TestListIsSplitOnCommas(t *testing.T) {
	list, err := Source{List: " " + first + ", ," + second + " "}.Accounts()
	if err != nil {
		t.Fatal(err)
	}
	if want := []Account{{first, "SGD"}, {second, "SGD"}}; !slices.Equal(list, want) {
		t.Fatalf("Accounts() = %v, want %v", list, want)
	}
}

// The sync writes each account with its currency; an ID alone, as in
// CRM_ACCOUNT_IDS, is in the deployment's currency.
func TestEntryMayGiveItsCurrency(t *testing.T) {
	list, err := Source{List: first + ":USD," + second + ": ,:EUR"}.Accounts()
	if err != nil {
		t.Fatal(err)
	}
	if want := []Account{{first, "USD"}, {second, "SGD"}}; !slices.Equal(list, want) {
		t.Fatalf("Accounts() = %v, want %v", list, want)
	}
}

func TestFileIsReadOnEveryCall(t *testing.T) {
	path := filepath.Join(t.TempDir(), "account-ids")
	source := Source{File: path, List: "ignored-when-a-file-is-set"}

	write(t, path, first+":SGD\r\n"+second+":USD\n\n")
	list, err := source.Accounts()
	if err != nil {
		t.Fatal(err)
	}
	if want := []Account{{first, "SGD"}, {second, "USD"}}; !slices.Equal(list, want) {
		t.Fatalf("Accounts() = %v, want %v", list, want)
	}

	write(t, path, "")
	list, err = source.Accounts()
	if err != nil {
		t.Fatal(err)
	}
	if len(list) != 0 {
		t.Fatalf("Accounts() after the file was emptied = %v, want none", list)
	}
}

func TestMissingFileIsAnError(t *testing.T) {
	_, err := Source{File: filepath.Join(t.TempDir(), "missing")}.Accounts()
	if err == nil {
		t.Fatal("Accounts() of a missing file returned no error")
	}
}

func TestSyncedOnlyWithAFile(t *testing.T) {
	if (Source{List: first}).Synced() {
		t.Error("a list alone is not synced")
	}
	if !(Source{File: "account-ids"}).Synced() {
		t.Error("a file is synced")
	}
}

func write(t *testing.T, path, content string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}
