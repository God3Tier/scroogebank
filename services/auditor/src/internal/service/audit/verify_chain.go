package audit

import (
	"auditor/src/internal/chain"
	"auditor/src/internal/models"
	"context"
)

// VerifyChain reads the chain a page at a time and checks each entry against
// the entries before it (chain.Checker). What to do about the problems is up
// to the caller.
func (i impl) VerifyChain(ctx context.Context) (int64, []models.ChainFinding, error) {
	var checker chain.Checker
	var findings []models.ChainFinding
	var checked, afterSequenceNo int64

	for {
		page, err := i.repo.GetChainPage(ctx, afterSequenceNo, i.pageSize)
		if err != nil {
			return 0, nil, err
		}
		for _, entry := range page {
			findings = append(findings, checker.Check(entry)...)
			afterSequenceNo = entry.SequenceNo
		}
		checked += int64(len(page))
		if len(page) < i.pageSize {
			return checked, findings, nil
		}
	}
}
