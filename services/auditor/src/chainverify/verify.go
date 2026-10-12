package main

import (
	"context"
	"log"

	auditSvc "auditor/src/internal/service/audit"
	corruptedLogSvc "auditor/src/internal/service/corrupted_logs"
)

// tamperDetected starts the log line for each new finding. A CloudWatch metric
// filter on it raises the chain-mismatch alarm (ADR-0022, ADR-0027).
const tamperDetected = "CHAIN_TAMPER_DETECTED"

// verify checks the chain, records each problem as a finding, and returns the
// exit status. The chain is never repaired, so a problem is found again on
// every run; only a problem not recorded before is logged for the alarm.
func verify(ctx context.Context, chain auditSvc.Service, findings corruptedLogSvc.Service) int {
	checked, problems, err := chain.VerifyChain(ctx)
	if err != nil {
		log.Printf("chain-verify: check the audit chain: %v", err)
		return exitFailed
	}

	newFindings := 0
	for _, problem := range problems {
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

	log.Printf("chain-verify: checked %d entries, found %d problems, %d of them new", checked, len(problems), newFindings)
	if newFindings > 0 {
		return exitNewFindings
	}
	return exitOK
}
