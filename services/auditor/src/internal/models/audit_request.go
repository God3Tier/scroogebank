package models

type AuditLogInput struct {
	// EventID identifies the originating SQS message for de-duplication.
	// Empty when the event has none; it is then stored as NULL.
	EventID       string
	EventType     string
	Action        string
	SourceService ServiceType
	ActorId       string
	Payload       string
}
