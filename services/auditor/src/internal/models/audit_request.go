package models

type AuditLogInput struct {
	EventType     string
	Action        string
	SourceService ServiceType
	ActorId       string
	Payload       string
}
