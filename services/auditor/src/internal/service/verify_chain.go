package service

import (
	"auditor/src/internal/pkg"
	"context"
	"fmt"
	"strconv"
	"time"
)

/*
 * This function verifies whether the chain is valid. If not, we can confirm that primary indx is out of sync
 * and we need to decide what to do with that error in audit
 */
func (i impl) VerifyChain(ctx context.Context) (map[int]error, error) {
	chainList, err := i.repo.GetChain(ctx)

	if err != nil {
		return nil, err
	}

	response := map[int]error{}

	for indx, chain := range chainList {
		if indx == len(chainList)-1 {
			break
		}

		rawString := chain.Id + strconv.FormatInt(chain.SequenceNo, 10) + chain.EventType + chain.Action + string(chain.SourceService) + chain.ActorId + chain.Timestamp.Format(time.RFC3339) + chain.Payload

		if pkg.Hash(rawString) != chainList[indx+1].ParentHash {
			response[indx] = InvalidChainError(fmt.Sprintf("Sequence %d and %d out of sync\n", indx, indx+1))
		}
	}

	return response, nil
}
