// Package audit records transaction reads for the Auditor (ADR-0064 decision
// 4). Each event carries the same fields user-service sent for these reads
// before, under source_service TRANSACTION_SERVICE.
package audit

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"regexp"
	"time"
	"uuid"

	"receiver/src/internal/models"
	"receiver/src/serverd/security"

	"github.com/gin-gonic/gin"
)

// saveTimeout bounds the outbox write that follows each request. The
// response has already been sent, so it can wait out a burst (ADR-0078).
const saveTimeout = 5 * time.Second

// fallbackTimeout bounds the direct send to the queue after a failed save.
const fallbackTimeout = 3 * time.Second

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

// WithFallback saves each event with save and, if that fails, sends it
// straight to the Auditor's queue with send, so a burst that holds every
// database connection loses no read events (ADR-0078). The event keeps its
// ID, and the Auditor drops a repeat by event_id. If both fail, the event is
// logged whole as AUDIT_SEND_FAILED, which is alarmed, for an operator to
// send again.
func WithFallback(save, send Save) Save {
	return func(ctx context.Context, event models.AuditEvent) error {
		saveErr := save(ctx, event)
		if saveErr == nil {
			return nil
		}
		// ctx may have expired with the failed save; the send gets its own time.
		sendCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), fallbackTimeout)
		defer cancel()
		sendErr := send(sendCtx, event)
		if sendErr == nil {
			log.Printf("audit event %s sent straight to the queue; the outbox save failed: %v", event.ID, saveErr)
			return nil
		}
		log.Printf("AUDIT_SEND_FAILED event_id=%s outbox_error=%q queue_error=%q audit_event=%s", event.ID, saveErr, sendErr, event.Body)
		return fmt.Errorf("audit event %s neither saved nor sent: %w", event.ID, errors.Join(saveErr, sendErr))
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
