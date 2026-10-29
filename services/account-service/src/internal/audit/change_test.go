package audit

import (
	"context"
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestRecordFillsTheChangeInTheContext(t *testing.T) {
	ctx, change := WithChange(context.Background())

	Record(ctx, "status", "PENDING", "ACTIVE")

	assert.Equal(t, Change{Attribute: "status", Before: "PENDING", After: "ACTIVE"}, *change)
}

func TestRecordWithoutAChangeDoesNothing(t *testing.T) {
	assert.NotPanics(t, func() { Record(context.Background(), "status", "PENDING", "ACTIVE") })
}
