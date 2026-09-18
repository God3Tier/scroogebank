package cronjob

import (
	"auditor/src/internal/models"
	"context"
	"database/sql"
	"encoding/json"
	"log"
)

func (i impl) ReadOffset(ctx context.Context, checked *Checked) {
	raw, err := i.svc.VerifyChainByOffset(ctx, checked.sequence, checked.offset)

	if err != nil {
		if err == sql.ErrNoRows {
			checked.sequence = 0
			return
		}
		log.Printf("Unable to get chain verify service %s\n", err.Error())
		return
	}

	if len(raw) == 0 {
		log.Printf("No issue with chain sequence between %d and %d\n", checked.sequence, checked.sequence+checked.offset)
		return
	}

	payload, err := json.Marshal(raw)

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
