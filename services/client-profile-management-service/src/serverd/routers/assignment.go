package routers

import (
	"context"
	"database/sql"
	"errors"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
)

type assignmentLookup func(context.Context, string) (string, time.Time, error)

var assignmentClientID = regexp.MustCompile(`(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

// RegisterAssignmentRoute exposes Contract B only to the dedicated machine
// client. It accepts no caller-controlled Agent identifier.
func RegisterAssignmentRoute(router *gin.Engine, authenticate gin.HandlerFunc, db *sql.DB) {
	registerAssignmentRoute(router, authenticate, func(ctx context.Context, clientID string) (string, time.Time, error) {
		var agent string
		var updated time.Time
		err := db.QueryRowContext(ctx, `SELECT assigned_agent_id, updated_at FROM clients
			WHERE client_id = $1 AND deleted_at IS NULL`, clientID).Scan(&agent, &updated)
		return agent, updated, err
	})
}

func registerAssignmentRoute(router *gin.Engine, authenticate gin.HandlerFunc, lookup assignmentLookup) {
	internal := router.Group("/internal/v1")
	internal.Use(authenticate)
	internal.GET("/clients/:clientId/assignment", func(c *gin.Context) {
		clientID := c.Param("clientId")
		if !assignmentClientID.MatchString(clientID) {
			c.Status(http.StatusNotFound)
			return
		}
		agent, updated, err := lookup(c.Request.Context(), clientID)
		if errors.Is(err, sql.ErrNoRows) {
			c.Status(http.StatusNotFound)
			return
		}
		if err != nil {
			c.Status(http.StatusServiceUnavailable)
			return
		}
		if strings.TrimSpace(agent) == "" {
			c.Status(http.StatusGone)
			return
		}
		c.JSON(http.StatusOK, gin.H{
			"schemaVersion": "1", "clientId": clientID,
			"assignedAgentSubject": agent, "active": true,
			"assignmentVersion": updated.UnixMicro(), "updatedAt": updated.UTC(),
		})
	})
}
