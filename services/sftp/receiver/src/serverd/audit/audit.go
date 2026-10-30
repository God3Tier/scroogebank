// Package audit records transaction reads for the Auditor (ADR-0064 decision
// 4). Each event carries the same fields user-service sent for these reads
// before, under source_service TRANSACTION_SERVICE.
package audit

import (
	"context"
	"encoding/json"
	"log"
	"regexp"
	"time"
	"uuid"

	"receiver/src/internal/models"
	"receiver/src/serverd/security"

	"github.com/gin-gonic/gin"
)

// saveTimeout bounds the outbox write that follows each request.
const saveTimeout = 2 * time.Second

const (
	eventType     = "CRUD"
	action        = "READ"
	sourceService = "TRANSACTION_SERVICE"
)

// A gin parameter, :name, as the {name} the Auditor's events use.
var ginParam = regexp.MustCompile(`:(\w+)`)

// Save writes one event to the outbox; outbox.Save in production.
type Save func(context.Context, models.AuditEvent) error

type event struct {
	EventID       string          `json:"event_id"`
	EventType     string          `json:"event_type"`
	Action        string          `json:"action"`
	SourceService string          `json:"source_service"`
	ActorID       string          `json:"actor_id"`
	Payload       json.RawMessage `json:"payload"`
}

type payload struct {
	ResourceType string `json:"resource_type"`
	ResourceID   string `json:"resource_id,omitempty"`
	Endpoint     string `json:"endpoint"`
	HTTPMethod   string `json:"http_method"`
	StatusCode   int    `json:"status_code"`
	DateTime     string `json:"datetime"`
	UserRole     string `json:"user_role,omitempty"`
}

// Read records a read of resourceType after the request is served, refused
// ones included. idParam names the route parameter holding the resource's
// ID, or is empty for a list. It must follow authentication: a request with
// no verified caller has no actor and is not recorded. A failed save is
// logged and dropped, and never changes the response.
func Read(save Save, resourceType, idParam string) gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Next()

		principal, ok := security.PrincipalFromGin(c)
		if !ok {
			return
		}
		recorded := payload{
			ResourceType: resourceType,
			Endpoint:     ginParam.ReplaceAllString(c.FullPath(), "{$1}"),
			HTTPMethod:   c.Request.Method,
			StatusCode:   c.Writer.Status(),
			DateTime:     time.Now().UTC().Format(time.RFC3339Nano),
			UserRole:     principal.Role,
		}
		if idParam != "" {
			recorded.ResourceID = c.Param(idParam)
		}
		auditEvent, err := encode(principal.Subject, recorded)
		if err != nil {
			log.Printf("could not encode transaction read audit event: %v", err)
			return
		}
		ctx, cancel := context.WithTimeout(context.WithoutCancel(c.Request.Context()), saveTimeout)
		defer cancel()
		if err := save(ctx, auditEvent); err != nil {
			log.Printf("could not save transaction read audit event_id=%s: %v", auditEvent.ID, err)
		}
	}
}

func encode(actorID string, recorded payload) (models.AuditEvent, error) {
	encodedPayload, err := json.Marshal(recorded)
	if err != nil {
		return models.AuditEvent{}, err
	}
	id := uuid.New().String()
	body, err := json.Marshal(event{
		EventID: id, EventType: eventType, Action: action,
		SourceService: sourceService, ActorID: actorID, Payload: encodedPayload,
	})
	if err != nil {
		return models.AuditEvent{}, err
	}
	return models.AuditEvent{ID: id, Body: body}, nil
}
