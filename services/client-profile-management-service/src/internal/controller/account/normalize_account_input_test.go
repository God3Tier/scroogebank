package account

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestNormalizeAccountInput(t *testing.T) {
	tests := map[string]struct {
		input string
		want  string
	}{
		"trims surrounding whitespace and uppercases": {input: "  savings ", want: "SAVINGS"},
		"uppercases lowercase value":                  {input: "active", want: "ACTIVE"},
		"preserves uppercase value":                   {input: "SGD", want: "SGD"},
	}
	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			assert.Equal(t, test.want, normalizeAccountInput(test.input))
		})
	}
}
