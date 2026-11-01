package routers

import (
	"context"
	"database/sql"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/assert"
)

func TestAssignmentContractFailsClosed(t *testing.T) {
	const clientID = "00000000-0000-0000-0000-000000000181"
	updated := time.Date(2026, 10, 28, 3, 0, 0, 0, time.UTC)
	tests := []struct {
		name, agent string
		err         error
		status      int
	}{
		{"active assignment", "agent-subject", nil, 200},
		{"missing client", "", sql.ErrNoRows, 404},
		{"inactive assignment", " ", nil, 410},
		{"database unavailable", "", errors.New("unavailable"), 503},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			gin.SetMode(gin.TestMode)
			router := gin.New()
			registerAssignmentRoute(router, func(c *gin.Context) { c.Next() },
				func(context.Context, string) (string, time.Time, error) {
					return test.agent, updated, test.err
				})
			res := httptest.NewRecorder()
			router.ServeHTTP(res, httptest.NewRequest(http.MethodGet,
				"/internal/v1/clients/"+clientID+"/assignment?agentId=attacker", nil))
			assert.Equal(t, test.status, res.Code)
			if test.status == 200 {
				assert.Contains(t, res.Body.String(), `"assignedAgentSubject":"agent-subject"`)
				assert.NotContains(t, res.Body.String(), "attacker")
				assert.True(t, strings.Contains(res.Body.String(), `"assignmentVersion":`))
			}
		})
	}
}
