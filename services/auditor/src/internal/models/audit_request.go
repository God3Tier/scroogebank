package models

type AuditLogInput struct {
	EventId       string
	EventType     string
	Action        string
	SourceService ServiceType
	ActorId       string
	Payload       string
}
