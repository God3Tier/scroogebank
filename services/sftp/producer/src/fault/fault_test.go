package fault

import (
	"bytes"
	"encoding/csv"
	"encoding/json"
	"testing"

	"producer/src/transaction"
)

var accountIDs = []string{"0b7c6f1e-4a0e-4f7c-9a52-6f1c3b2d8e11"}

// rejected reports whether the import would reject a row, by the rules in
// the receiver's normalizeTransaction.
func rejected(row transaction.Transaction) bool {
	validType := row.TransactionType == "DEBIT" || row.TransactionType == "CREDIT"
	validStatus := row.Status == "COMPLETED" || row.Status == "PENDING" || row.Status == "FAILED"
	return row.TransactionID == "" || row.Amount < 0 || !validType || !validStatus || len(row.Currency) != 3
}

func build(t *testing.T, kind string, wantFiles int) []File {
	t.Helper()
	files, err := Build(kind, accountIDs)
	if err != nil {
		t.Fatalf("Build(%q): %v", kind, err)
	}
	if len(files) != wantFiles {
		t.Fatalf("Build(%q) returned %d files, want %d", kind, len(files), wantFiles)
	}
	return files
}

func rows(t *testing.T, file File) []transaction.Transaction {
	t.Helper()
	if file.Extension != ".json" {
		t.Fatalf("extension %q, want .json", file.Extension)
	}
	var rows []transaction.Transaction
	if err := json.Unmarshal(file.Content, &rows); err != nil {
		t.Fatalf("parse %s: %v", file.Content, err)
	}
	return rows
}

func countRejected(rows []transaction.Transaction) int {
	n := 0
	for _, row := range rows {
		if rejected(row) {
			n++
		}
	}
	return n
}

func TestCorruptCannotBeParsed(t *testing.T) {
	for range 50 {
		file := build(t, "corrupt", 1)[0]
		var anything any
		if file.Extension != ".json" || len(file.Content) == 0 || json.Unmarshal(file.Content, &anything) == nil {
			t.Fatalf("corrupt file %q parses or is not a .json file", file.Content)
		}
	}
}

func TestPartialHasValidAndInvalidRows(t *testing.T) {
	rows := rows(t, build(t, "partial", 1)[0])
	if len(rows) != validRows+5 || countRejected(rows) != 5 {
		t.Fatalf("got %d rows, %d rejected; want %d rows, 5 rejected", len(rows), countRejected(rows), validRows+5)
	}
}

func TestAllInvalidHasOnlyInvalidRows(t *testing.T) {
	rows := rows(t, build(t, "all-invalid", 1)[0])
	if len(rows) == 0 || countRejected(rows) != len(rows) {
		t.Fatalf("got %d rows, %d rejected; want every row rejected", len(rows), countRejected(rows))
	}
}

func TestUnsupportedIsACSVFile(t *testing.T) {
	file := build(t, "unsupported", 1)[0]
	if file.Extension != ".csv" {
		t.Fatalf("extension %q, want .csv", file.Extension)
	}
	records, err := csv.NewReader(bytes.NewReader(file.Content)).ReadAll()
	if err != nil || len(records) != validRows+1 {
		t.Fatalf("got %d CSV records (%v), want a header and %d rows", len(records), err, validRows)
	}
}

func TestResendSendsThePendingTransactionAgainAsCompleted(t *testing.T) {
	files := build(t, "resend", 2)
	first, second := rows(t, files[0]), rows(t, files[1])
	if len(first) != 1 || len(second) != 1 {
		t.Fatalf("got %d and %d rows, want one in each file", len(first), len(second))
	}
	if first[0].TransactionID != second[0].TransactionID || first[0].Status != "PENDING" || second[0].Status != "COMPLETED" ||
		first[0].Amount == second[0].Amount || rejected(first[0]) || rejected(second[0]) {
		t.Fatalf("got %+v then %+v, want the same valid transaction, Pending then Completed, with another amount", first[0], second[0])
	}
}

func TestBuildRefusesAnUnknownKind(t *testing.T) {
	if _, err := Build("missing", accountIDs); err == nil {
		t.Fatal("Build accepted an unknown kind")
	}
}
