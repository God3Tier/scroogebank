package cronjob

import (
	"auditor/src/internal/models"
	"context"
	"encoding/json"
	"log"
)

func (i impl) VerifyHistoricalChain(ctx context.Context) {

	invalid_chain, err := i.svc.VerifyChain(ctx)

	if err != nil {
		log.Printf("Error running verify chain: %s\n", err.Error())
		return 
	}

	if len(invalid_chain) == 0 {
		log.Printf("Chain remains consistent\n")
		return
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

}
