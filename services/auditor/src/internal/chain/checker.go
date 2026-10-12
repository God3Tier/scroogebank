package chain

import "auditor/src/internal/models"

// Checker checks the entries of the audit chain one at a time, oldest first,
// then Finish reports what was missing. The zero value is ready to check the
// first entry.
type Checker struct {
	// Anchor is the latest S3 anchor, or nil when none has been written. The
	// entry it names must still have the anchored id and hash.
	Anchor *models.ChainAnchor

	started          bool
	anchorFound      bool
	previousHash     string
	previousSequence int64
}

// Check returns the problems with entry, given the entries checked before it:
//   - its fields must produce its stored hash;
//   - its parent_hash must be the previous entry's stored hash, or empty for
//     the first entry;
//   - its sequence number must follow the previous entry's, or be 1 for the
//     first entry;
//   - if the anchor names its sequence number, it must have the anchored id
//     and hash.
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
	if c.Anchor != nil && entry.SequenceNo == c.Anchor.SequenceNo {
		c.anchorFound = true
		if entry.Id != c.Anchor.ID || entry.Hash != c.Anchor.Hash {
			findings = append(findings, models.ChainFinding{Entry: entry, Reason: models.AnchorMismatch})
		}
	}

	c.started, c.previousHash, c.previousSequence = true, entry.Hash, entry.SequenceNo
	return findings
}

// Finish returns the problem left once every entry has been checked: the
// entry the anchor names is gone, because the chain was cut back below it or
// the entry was deleted. The finding holds what the anchor recorded.
func (c *Checker) Finish() []models.ChainFinding {
	if c.Anchor == nil || c.anchorFound {
		return nil
	}
	missing := models.AuditLog{Id: c.Anchor.ID, SequenceNo: c.Anchor.SequenceNo, Hash: c.Anchor.Hash}
	return []models.ChainFinding{{Entry: missing, Reason: models.AnchorMismatch}}
}
