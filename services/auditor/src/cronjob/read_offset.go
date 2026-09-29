package cronjob

import (
	"auditor/src/internal/models"
	"context"
	"database/sql"
	"encoding/json"
	"log"
)

func (i impl) ReadOffset(ctx context.Context, checked *Checked) {
	invalid_chain, err := i.auditSvc.VerifyChainByOffset(ctx, checked.sequence, checked.offset)

	if err != nil {
		if err == sql.ErrNoRows {
			checked.sequence = 0
			return
		}
		log.Printf("Unable to get chain verify service %s\n", err.Error())
		return
	}

	if len(invalid_chain) == 0 {
		log.Printf("No issue with chain sequence between %d and %d\n", checked.sequence, checked.sequence+checked.offset)
		return
	}

	for hash, _ := range invalid_chain {
		err := i.auditSvc.DeleteAuditLog(ctx, hash)
		if err != nil {
			log.Printf("Unable to get remove audit chain  %s\n", err.Error())
		}
	}

	payload, err := json.Marshal(invalid_chain)

	if err != nil {
		log.Printf("Unable to marshal errors\n")
		return
	}

	chain_log := models.AuditLogInput{
		EventType:     "Historical",
		Action:        "Error logging",
		SourceService: models.AuditService,
		ActorId:       "Historical-chain",
		Payload:       string(payload),
	}

	if err := i.repo.StoreAuditChain(ctx, chain_log); err != nil {
		log.Printf("Unable to store in chain %s\n", err.Error())
	}
	checked.sequence += checked.offset - 1
}
