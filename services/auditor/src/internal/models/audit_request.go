package models

type AuditLogInput struct {
	// EventID identifies the originating SQS message for de-duplication.
	// Empty for entries generated internally (e.g. by the cronjob) rather
	// than received from the queue.
	EventID       string
	EventType     string
	Action        string
	SourceService ServiceType
	ActorId       string
	Payload       string
}
