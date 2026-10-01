package audit 

import (
	"errors"
)

var (

	UnimplementedRepositoryError = errors.New("Error function not yet implemented")

	// DuplicateEventError indicates the EventID on this request was already
	// stored — expected when SQS redelivers a message the auditor already
	// processed (standard queues deliver at-least-once). Callers should
	// treat this as safe to acknowledge/delete, not as a failure to retry.
	DuplicateEventError = errors.New("event_id already processed")
)
