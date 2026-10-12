package models

// FindingReason says what chain-verify found wrong with an audit_log row
// (ADR-0022 decision 4).
type FindingReason string

const (
	// HashMismatch: the row's fields no longer produce its stored hash, so the
	// row was changed after it was written.
	HashMismatch FindingReason = "HASH_MISMATCH"
	// ParentMismatch: the row's parent_hash is not the previous row's hash, so
	// a row before it was deleted, added, or changed and given a new hash.
	ParentMismatch FindingReason = "PARENT_MISMATCH"
	// SequenceGap: the row's sequence number does not follow the previous
	// row's, so rows before it are missing.
	SequenceGap FindingReason = "SEQUENCE_GAP"
	// AnchorMismatch: the row the latest S3 anchor names is missing or has
	// another id or hash, so the chain was rewritten with every hash
	// recomputed, or cut back below the anchor.
	AnchorMismatch FindingReason = "ANCHOR_MISMATCH"
)

// ChainFinding is one problem chain-verify found with one audit_log row.
type ChainFinding struct {
	Entry  AuditLog
	Reason FindingReason
}
