package cronjob

import (
	"auditor/src/internal/models"
	"auditor/src/internal/repository/audit"
	"auditor/src/internal/service"
	"context"
	"errors"
	"testing"
)

func TestVerifyHistoricalChain(t *testing.T) {
	ctx := context.Background()
	databaseErr := errors.New("database unavailable")

	tests := map[string]struct {
		invalidChain map[int]error
		svcErr       error
		wantLog      *models.AuditLogInput
	}{
		"does nothing when chain is valid": {
			invalidChain: map[int]error{},
			wantLog:      nil,
		},

		"stores invalid chain errors": {
			invalidChain: map[int]error{
				1: service.InvalidChainError("Sequence 1 and 2 out of sync\n"),
				3: service.InvalidChainError("Sequence 3 and 4 out of sync\n"),
			},
			wantLog: &models.AuditLogInput{
				EventType:     "Historical",
				Action:        "Error logging",
				SourceService: models.AuditService,
				ActorId:       "Historical-chain",
				Payload:       `{"1":{},"3":{}}`,
			},
		},

		"does nothing when verify chain returns an error": {
			invalidChain: nil,
			svcErr:       databaseErr,
			wantLog:      nil,
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			svc := service.NewMockAudit(t)
			repo := audit.NewMockRepository(t)

			t.Cleanup(func() {
				svc.AssertExpectations(t)
				repo.AssertExpectations(t)
			})

			svc.On("VerifyChain", ctx).
				Return(test.invalidChain, test.svcErr).
				Once()

			if test.wantLog != nil {
				repo.On(
					"StoreAuditChain",
					ctx,
					*test.wantLog,
				).
					Return(nil).
					Once()
			}

			New(svc, repo).VerifyHistoricalChain(ctx)
		})
	}
}
