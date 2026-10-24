// Package accounts says which CRM accounts the producer's transactions refer
// to, and in which currency (ADR-0045).
package accounts

import (
	"fmt"
	"os"
	"strings"
	"unicode"
)

// DefaultCurrency is the currency of an account listed without one: SGD, the
// currency the specification gives as its example (Appendix 2). Accounts in
// other currencies are listed with theirs (ADR-0045).
const DefaultCurrency = "SGD"

// Account is one CRM account and the currency it is held in, so its
// transactions are in that currency.
type Account struct {
	ID       string
	Currency string
}

// Source is where the accounts come from.
type Source struct {
	// File, from CRM_ACCOUNT_IDS_FILE, is read on every run, so
	// scripts/sync-producer-accounts.sh can change it without a restart.
	// The deployed server sets it.
	File string
	// List, from CRM_ACCOUNT_IDS, is used when File is not set, as in the
	// local Compose stack.
	List string
}

// FromEnv reads the source from the environment.
func FromEnv() Source {
	return Source{File: os.Getenv("CRM_ACCOUNT_IDS_FILE"), List: os.Getenv("CRM_ACCOUNT_IDS")}
}

// Synced reports whether the accounts come from the synced file. A synced
// file that lists no account means the CRM has none, so nothing is
// published. An empty List instead means random external references, which
// the import stores as Unmatched (ADR-0037).
func (s Source) Synced() bool {
	return s.File != ""
}

// Accounts returns the accounts, reading the file afresh each time.
func (s Source) Accounts() ([]Account, error) {
	if !s.Synced() {
		return parse(s.List), nil
	}
	content, err := os.ReadFile(s.File)
	if err != nil {
		return nil, fmt.Errorf("read CRM account IDs: %w", err)
	}
	return parse(string(content)), nil
}

// parse takes entries separated by commas or white space, so both the
// comma-separated variable and the file's one entry per line work. An entry
// is an ID, or an ID and its currency as <id>:<currency>; a bare ID is in
// DefaultCurrency.
func parse(text string) []Account {
	entries := strings.FieldsFunc(text, func(r rune) bool { return r == ',' || unicode.IsSpace(r) })
	list := make([]Account, 0, len(entries))
	for _, entry := range entries {
		id, currency, _ := strings.Cut(entry, ":")
		if id == "" {
			continue
		}
		if currency == "" {
			currency = DefaultCurrency
		}
		list = append(list, Account{ID: id, Currency: currency})
	}
	return list
}
