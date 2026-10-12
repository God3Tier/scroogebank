package chain

import "auditor/src/internal/models"

// Checker checks the entries of the audit chain one at a time, oldest first.
// The zero value is ready to check the first entry.
type Checker struct {
	started          bool
	previousHash     string
	previousSequence int64
}

// Check returns the problems with entry, given the entries checked before it:
//   - its fields must produce its stored hash;
//   - its parent_hash must be the previous entry's stored hash, or empty for
//     the first entry;
//   - its sequence number must follow the previous entry's, or be 1 for the
//     first entry.
//
// The next entry is compared with this entry's stored hash, not a recomputed
// one, so an edited entry is reported once, where it was edited.
func (c *Checker) Check(entry models.AuditLog) []models.ChainFinding {
	wantParent, wantSequence := "", int64(1)
	if c.started {
		wantParent, wantSequence = c.previousHash, c.previousSequence+1
	}

	var findings []models.ChainFinding
	if Hash(entry) != entry.Hash {
		findings = append(findings, models.ChainFinding{Entry: entry, Reason: models.HashMismatch})
	}
	if entry.ParentHash != wantParent {
		findings = append(findings, models.ChainFinding{Entry: entry, Reason: models.ParentMismatch})
	}
	if entry.SequenceNo != wantSequence {
		findings = append(findings, models.ChainFinding{Entry: entry, Reason: models.SequenceGap})
	}

	c.started, c.previousHash, c.previousSequence = true, entry.Hash, entry.SequenceNo
	return findings
}
