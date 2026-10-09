package sqs

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
)

const (
	eventType          = "CRUD"
	sourceService      = "CRM"
	maxRequestBodySize = 1 << 20
)

type Event struct {
	EventID       string          `json:"event_id"`
	EventType     string          `json:"event_type"`
	Action        string          `json:"action"`
	SourceService string          `json:"source_service"`
	ActorID       string          `json:"actor_id"`
	Payload       json.RawMessage `json:"payload"`
}

// EndpointPayload records one completed API call without copying user PII from
// the request or response bodies into the audit queue.
type EndpointPayload struct {
	AttributeName string `json:"attribute_name"`
	AgentID       string `json:"agent_id"`
	ClientID      string `json:"client_id,omitempty"`
	Endpoint      string `json:"endpoint"`
	HTTPMethod    string `json:"http_method"`
	StatusCode    int    `json:"status_code"`
	DateTime      string `json:"datetime"`
}

// AuditLogger saves one event to the outbox after each protected CRM endpoint
// finishes; Relay sends it to the Auditor queue (ADR-0020). Authentication
// middleware must run before this middleware and place the verified Cognito
// subject in Gin's "agent_id" context value.
func AuditLogger(outbox Outbox) gin.HandlerFunc {
	return func(c *gin.Context) {
		// Preserve only the client ID from a JSON body before the controller reads
		// it. The body is restored and never included in the audit event.
		clientID := c.Param("clientId")
		if clientID == "" {
			clientID = c.Param("accountId")
		}
		if clientID == "" {
			// GET /api/accounts?clientId= names the client in the query string.
			clientID = c.Query("clientId")
		}
		if clientID == "" && c.Request.Body != nil {
			body, err := io.ReadAll(io.LimitReader(c.Request.Body, maxRequestBodySize+1))
			if err != nil || len(body) > maxRequestBodySize {
				c.AbortWithStatusJSON(http.StatusBadRequest, gin.H{"error": "invalid request body"})
				return
			}
			c.Request.Body = io.NopCloser(bytes.NewReader(body))
			var request struct {
				ClientID      string `json:"client_id"`
				ClientIDCamel string `json:"clientId"`
			}
			if json.Unmarshal(body, &request) == nil {
				clientID = request.ClientID
				if clientID == "" {
					clientID = request.ClientIDCamel
				}
			}
		}

		c.Next()

		actorID, ok := c.Get("agent_id")
		actor, ok := actorID.(string)
		if !ok || strings.TrimSpace(actor) == "" {
			log.Printf("audit event not sent: verified agent_id missing for %s %s", c.Request.Method, c.FullPath())
			return
		}
		action, ok := actionForRequest(c.Request.Method, c.FullPath())
		if !ok {
			log.Printf("audit event not sent: unsupported CRM endpoint method=%s route=%s", c.Request.Method, c.FullPath())
			return
		}

		eventID, err := newEventID()
		if err != nil {
			log.Printf("audit event not sent: generate event ID: %v", err)
			return
		}
		payload, err := json.Marshal(EndpointPayload{
			AttributeName: "endpoint_call",
			AgentID:       actor,
			ClientID:      clientID,
			Endpoint:      c.FullPath(),
			HTTPMethod:    c.Request.Method,
			StatusCode:    c.Writer.Status(),
			DateTime:      time.Now().UTC().Format(time.RFC3339Nano),
		})
		if err != nil {
			log.Printf("audit event not sent: marshal payload: %v", err)
			return
		}
		message, err := json.Marshal(Event{
			EventID:       eventID,
			EventType:     eventType,
			Action:        action,
			SourceService: sourceService,
			ActorID:       actor,
			Payload:       payload,
		})
		if err != nil {
			log.Printf("audit event not sent: marshal event: %v", err)
			return
		}
		// The endpoint's change has committed even if the caller has since gone
		// away, so the save must not be cancelled with the request.
		ctx, cancel := context.WithTimeout(context.WithoutCancel(c.Request.Context()), saveTimeout)
		defer cancel()
		if err := outbox.Save(ctx, eventID, message); err != nil {
			log.Printf("audit event not saved event_id=%s route=%s: %v", eventID, c.FullPath(), err)
		}
	}
}

func actionForRequest(method, route string) (string, bool) {
	if strings.HasSuffix(route, "/verify") && method == http.MethodPost {
		return "UPDATE", true
	}
	switch method {
	case http.MethodPost:
		return "CREATE", true
	case http.MethodGet:
		return "GET", true
	case http.MethodPut, http.MethodPatch:
		return "UPDATE", true
	case http.MethodDelete:
		return "DELETE", true
	default:
		return "", false
	}
}

func newEventID() (string, error) {
	var id [16]byte
	if _, err := rand.Read(id[:]); err != nil {
		return "", fmt.Errorf("read random UUID bytes: %w", err)
	}
	id[6] = (id[6] & 0x0f) | 0x40
	id[8] = (id[8] & 0x3f) | 0x80
	encoded := hex.EncodeToString(id[:])
	return encoded[:8] + "-" + encoded[8:12] + "-" + encoded[12:16] + "-" + encoded[16:20] + "-" + encoded[20:], nil
}
