package main

import (
	"context"
	"log"
	"time"

	"auditor/src/internal/models"
	"auditor/src/internal/repository/anchor"
	auditSvc "auditor/src/internal/service/audit"
	corruptedLogSvc "auditor/src/internal/service/corrupted_logs"
)

// tamperDetected starts the log line for each new finding. A CloudWatch metric
// filter on it raises the chain-mismatch alarm (ADR-0022, ADR-0027).
const tamperDetected = "CHAIN_TAMPER_DETECTED"

// verify checks the chain against the latest anchor, records each problem as
// a finding, anchors the current head, and returns the exit status. The chain
// is never repaired, so a problem is found again on every run; only a problem
// not recorded before is logged for the alarm.
func verify(ctx context.Context, chain auditSvc.Service, findings corruptedLogSvc.Service, anchors anchor.Repository) int {
	latest, err := anchors.Latest(ctx)
	if err != nil {
		log.Printf("chain-verify: read the latest anchor: %v", err)
		return exitFailed
	}

	report, err := chain.VerifyChain(ctx, latest)
	if err != nil {
		log.Printf("chain-verify: check the audit chain: %v", err)
		return exitFailed
	}

	newFindings := 0
	for _, problem := range report.Findings {
		isNew, err := findings.RecordFinding(ctx, problem)
		if err != nil {
			log.Printf("chain-verify: record a finding: %v", err)
			return exitFailed
		}
		if isNew {
			newFindings++
			log.Printf("%s reason=%s sequence_no=%d audit_log_id=%s",
				tamperDetected, problem.Reason, problem.Entry.SequenceNo, problem.Entry.Id)
		}
	}

	// The head is anchored even when problems were found: the chain is never
	// repaired, so holding back would leave every later entry unanchored.
	anchored := "nothing to anchor in an empty chain"
	if report.Head != nil {
		head := models.ChainAnchor{
			SequenceNo: report.Head.SequenceNo,
			ID:         report.Head.Id,
			Hash:       report.Head.Hash,
			AnchoredAt: time.Now().UTC().Truncate(time.Microsecond),
		}
		if err := anchors.Write(ctx, head); err != nil {
			log.Printf("chain-verify: write the anchor: %v", err)
			return exitFailed
		}
		anchored = "anchored the head"
	}

	log.Printf("chain-verify: checked %d entries, found %d problems, %d of them new; %s",
		report.Checked, len(report.Findings), newFindings, anchored)
	if newFindings > 0 {
		return exitNewFindings
	}
	return exitOK
}
