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
	ids, err := Source{List: " " + first + ", ," + second + " "}.IDs()
	if err != nil {
		t.Fatal(err)
	}
	if want := []string{first, second}; !slices.Equal(ids, want) {
		t.Fatalf("IDs() = %q, want %q", ids, want)
	}
}

func TestFileIsReadOnEveryCall(t *testing.T) {
	path := filepath.Join(t.TempDir(), "account-ids")
	source := Source{File: path, List: "ignored-when-a-file-is-set"}

	write(t, path, first+"\r\n"+second+"\n\n")
	ids, err := source.IDs()
	if err != nil {
		t.Fatal(err)
	}
	if want := []string{first, second}; !slices.Equal(ids, want) {
		t.Fatalf("IDs() = %q, want %q", ids, want)
	}

	write(t, path, "")
	ids, err = source.IDs()
	if err != nil {
		t.Fatal(err)
	}
	if len(ids) != 0 {
		t.Fatalf("IDs() after the file was emptied = %q, want none", ids)
	}
}

func TestMissingFileIsAnError(t *testing.T) {
	_, err := Source{File: filepath.Join(t.TempDir(), "missing")}.IDs()
	if err == nil {
		t.Fatal("IDs() of a missing file returned no error")
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
