// Package chain computes and checks the hashes of the audit chain
// (ADR-0022 decisions 1 and 3).
package chain

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strconv"
	"time"

	"auditor/src/internal/models"
)

// version is the first item of every encoding. A different encoding needs a
// new version, so the old and new ones can never be confused.
const version = "audit-chain-v1"

// timestampLayout writes a UTC timestamp at the microsecond precision
// Postgres stores it with. The trailing Z is a literal.
const timestampLayout = "2006-01-02T15:04:05.000000Z"

// Now returns the current time as audit_log stores it: in UTC, truncated to
// microseconds, so the time read back is the time that was hashed.
func Now() time.Time {
	return time.Now().UTC().Truncate(time.Microsecond)
}

// Hash returns the hash of entry: SHA-256, in lowercase hex, over the version
// and then each field in a fixed order. Each item is written as its length in
// bytes, a colon, its bytes and a comma, so no two different entries encode
// the same way. entry.Hash itself is not part of it.
func Hash(entry models.AuditLog) string {
	digest := sha256.New()
	for _, item := range []string{
		version,
		entry.ID,
		strconv.FormatInt(entry.SequenceNo, 10),
		entry.EventID,
		entry.EventType,
		entry.Action,
		string(entry.SourceService),
		entry.ActorID,
		entry.Timestamp.UTC().Format(timestampLayout),
		entry.Payload,
		entry.ParentHash,
	} {
		fmt.Fprintf(digest, "%d:%s,", len(item), item)
	}
	return hex.EncodeToString(digest.Sum(nil))
}
