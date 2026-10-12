package audit

import (
	"auditor/src/internal/chain"
	"auditor/src/internal/models"
	"context"
)

// VerifyChain reads the chain a page at a time and checks each entry against
// the entries before it and against the anchor (chain.Checker). What to do
// about the problems is up to the caller.
func (i impl) VerifyChain(ctx context.Context, anchor *models.ChainAnchor) (models.ChainReport, error) {
	checker := chain.Checker{Anchor: anchor}
	var report models.ChainReport
	var afterSequenceNo int64

	for {
		page, err := i.repo.GetChainPage(ctx, afterSequenceNo, i.pageSize)
		if err != nil {
			return models.ChainReport{}, err
		}
		for _, entry := range page {
			report.Findings = append(report.Findings, checker.Check(entry)...)
			afterSequenceNo = entry.SequenceNo
		}
		report.Checked += int64(len(page))
		if len(page) > 0 {
			head := page[len(page)-1]
			report.Head = &head
		}
		if len(page) < i.pageSize {
			break
		}
	}

	report.Findings = append(report.Findings, checker.Finish()...)
	return report, nil
}
