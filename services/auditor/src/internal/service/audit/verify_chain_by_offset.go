package audit

import (
	"auditor/src/internal/pkg"
	"auditor/src/internal/service"
	"context"
	"fmt"
	"strconv"
	"time"
)

func (i impl) VerifyChainByOffset(ctx context.Context, beginChain int, offset int) (map[string]error, error) {
	chainList, err := i.repo.GetChainByOffset(ctx, beginChain, offset)

	if err != nil {
		return nil, err
	}

	response := map[string]error{}

	for indx, chain := range chainList {
		if indx == len(chainList)-1 {
			break
		}

		rawString := chain.Id + strconv.FormatInt(chain.SequenceNo, 10) + chain.EventType + chain.Action + string(chain.SourceService) + chain.ActorId + chain.Timestamp.Format(time.RFC3339) + chain.Payload

		if pkg.Hash(rawString) != chainList[indx+1].ParentHash {
			response[chain.Hash] = service.InvalidChainError(fmt.Sprintf("Sequence %d and %d out of sync\n", indx, indx+1))
		}
	}

	return response, nil

}
