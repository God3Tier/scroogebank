package service

import (
	"auditor/src/internal/pkg"
	"context"
	"fmt"
	"strconv"
	"time"
)

func (i impl) VerifyChainByOffset(ctx context.Context, beginChain int, offset int) (map[int]error, error) {
	chainList, err := i.repo.GetChainByOffset(ctx, beginChain, offset)

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
