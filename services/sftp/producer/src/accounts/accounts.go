// Package accounts says which CRM accounts the producer's transactions refer
// to (ADR-0045).
package accounts

import (
	"fmt"
	"os"
	"strings"
	"unicode"
)

// Source is where the account IDs come from.
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

// Synced reports whether the IDs come from the synced file. A synced file
// that lists no account means the CRM has none, so nothing is published.
// An empty List instead means random external references, which the import
// stores as Unmatched (ADR-0037).
func (s Source) Synced() bool {
	return s.File != ""
}

// IDs returns the account IDs, reading the file afresh each time.
func (s Source) IDs() ([]string, error) {
	if !s.Synced() {
		return split(s.List), nil
	}
	content, err := os.ReadFile(s.File)
	if err != nil {
		return nil, fmt.Errorf("read CRM account IDs: %w", err)
	}
	return split(string(content)), nil
}

// split takes IDs separated by commas or white space, so both the
// comma-separated variable and the file's one ID per line work.
func split(text string) []string {
	return strings.FieldsFunc(text, func(r rune) bool { return r == ',' || unicode.IsSpace(r) })
}
