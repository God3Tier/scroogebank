package encryption

import "strings"

// NormalizeEmail returns the form of an email address that its blind index is
// computed over: trimmed and lowercased, so two addresses that differ only in
// case belong to the same client (ADR-0030 decision 6).
func NormalizeEmail(email string) string {
	return strings.ToLower(strings.TrimSpace(email))
}

// NormalizePhone returns the form of a phone number that its blind index is
// computed over: trimmed, with spaces, hyphens, dots and brackets removed. A
// leading + is kept and no country code is assumed, so "60000101" and
// "+6560000101" stay different numbers.
func NormalizePhone(phone string) string {
	return strings.Map(func(r rune) rune {
		switch r {
		case ' ', '\t', '-', '.', '(', ')':
			return -1
		}
		return r
	}, strings.TrimSpace(phone))
}
