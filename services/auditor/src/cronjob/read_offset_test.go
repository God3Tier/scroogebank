package cronjob

import (
	"auditor/src/internal/models"
	"auditor/src/internal/repository/audit"
	"auditor/src/internal/service"
	auditSvc "auditor/src/internal/service/audit"
	"context"
	"errors"
	"testing"
)

func TestReadOffset(t *testing.T) {
	ctx := context.Background()
	databaseErr := errors.New("database unavailable")

	tests := map[string]struct {
		sequence     int
		offset       int
		invalidChain map[string]error
		svcErr       error
		wantLog      *models.AuditLogInput
	}{
		"does nothing when chain is valid": {
			sequence:     1,
			offset:       2,
			invalidChain: map[string]error{},
			wantLog:      nil,
		},
		"stores invalid chain errors": {
			sequence: 1,
			offset:   3,
			invalidChain: map[string]error{
				"hash-1": service.InvalidChainError("Sequence 1 and 2 out of sync\n"),
				"hash-3": service.InvalidChainError("Sequence 3 and 4 out of sync\n"),
			},
			wantLog: &models.AuditLogInput{
				EventType:     "Historical",
				Action:        "Error logging",
				SourceService: models.AuditService,
				ActorId:       "Historical-chain",
				Payload:       `{"hash-1":{},"hash-3":{}}`,
			},
		},
		"does nothing when verify chain returns an error": {
			sequence:     1,
			offset:       2,
			invalidChain: nil,
			svcErr:       databaseErr,
			wantLog:      nil,
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			svc := auditSvc.NewMockService(t)
			repo := audit.NewMockRepository(t)

			t.Cleanup(func() {
				svc.AssertExpectations(t)
				repo.AssertExpectations(t)
			})

			svc.On(
				"VerifyChainByOffset",
				ctx,
				test.sequence,
				test.offset,
			).Return(test.invalidChain, test.svcErr).Once()

			for hash := range test.invalidChain {
				svc.On("DeleteAuditLog", ctx, hash).
					Return(nil).
					Once()
			}

			if test.wantLog != nil {
				repo.On(
					"StoreAuditChain",
					ctx,
					*test.wantLog,
				).Return(nil).Once()
			}

			New(svc, repo).ReadOffset(ctx, &Checked{
				sequence: test.sequence,
				offset:   test.offset,
			})
		})
	}
}
