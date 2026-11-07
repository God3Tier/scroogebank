package client

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestServerErrorKeepsTheCauseOutOfTheResponse(t *testing.T) {
	res := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/clients/x/documents", nil)

	writeServerError(res, req, errors.New(`pq: invalid input syntax for type uuid: "x"`))

	assert.Equal(t, http.StatusInternalServerError, res.Code)
	assert.Equal(t, "internal server error\n", res.Body.String())
}
