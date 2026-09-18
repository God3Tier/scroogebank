package pkg

import (
	"crypto/sha256"
	"fmt"
)

func Hash(payload string) string {
	raw := sha256.Sum256([]byte(payload))
	return fmt.Sprintf("%x", raw)
}
