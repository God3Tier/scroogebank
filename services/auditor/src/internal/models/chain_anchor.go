package models

import "time"

// ChainAnchor is a chain head that chain-verify writes to the S3 Object Lock
// bucket on each run (ADR-0022 decision 3). Each entry's hash covers every
// entry before it, so the anchor stands for the whole chain up to that entry,
// and a later rewrite of that history changes the entry's hash or removes it.
type ChainAnchor struct {
	SequenceNo int64     `json:"sequence_no"`
	ID         string    `json:"id"`
	Hash       string    `json:"hash"`
	AnchoredAt time.Time `json:"anchored_at"`
}

// ChainReport is what one check of the whole chain found.
type ChainReport struct {
	Checked  int64
	Findings []ChainFinding
	// Head is the newest entry, or nil when the chain is empty.
	Head *AuditLog
}
