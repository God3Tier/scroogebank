package transaction

import "testing"

// The deployment holds one currency, so a valid transaction is never in
// another one: the dashboard refuses to add amounts across currencies.
func TestGenerateUsesOnlySGD(t *testing.T) {
	for range 1000 {
		if got := Generate([]string{"0b7c6f1e-4a0e-4f7c-9a52-6f1c3b2d8e11"}).Currency; got != "SGD" {
			t.Fatalf("Generate() currency = %q, want SGD", got)
		}
	}
}

// Only the rows made unsupported on purpose leave SGD.
func TestGenerateRandomSetUsesSGDOrTheUnsupportedFault(t *testing.T) {
	for range 1000 {
		for _, row := range GenerateRandomSet() {
			if row.Currency != "SGD" && row.Currency != "Unsupported" {
				t.Fatalf("GenerateRandomSet() currency = %q, want SGD or the Unsupported fault", row.Currency)
			}
		}
	}
}
