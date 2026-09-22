package client

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
	"uuid"

	clienterrors "client-profile-management-service/src/internal/errors"
	"client-profile-management-service/src/internal/models"
)

func newUUIDString(t *testing.T) string {
	t.Helper()
	return uuid.NewV4().String()
}

type fakeClientService struct {
	createErr error
	updateErr error
	getErr    error
	deleteErr error
	getClient *models.Client
	lastID    string
}

func (f *fakeClientService) CreateClientProfile(ctx context.Context, client models.Client) error {
	return f.createErr
}

func (f *fakeClientService) VerifyClientIdentity(ctx context.Context, clientID string) error {
	return nil
}

func (f *fakeClientService) UpdateClientInformation(ctx context.Context, client models.Client) error {
	return f.updateErr
}

func (f *fakeClientService) GetClientProfile(ctx context.Context, clientID string) (*models.Client, error) {
	return f.getClient, f.getErr
}

func (f *fakeClientService) DeleteClientProfile(ctx context.Context, clientID string) error {
	return f.deleteErr
}

func TestClientControllerCreateClientProfile_BadJSON(t *testing.T) {
	ctrl := &clientController{svc: &fakeClientService{}}
	req := httptest.NewRequest(http.MethodPost, "/clients", strings.NewReader("{not-json"))
	res := httptest.NewRecorder()

	ctrl.CreateClientProfile(res, req)
	if res.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want %d", res.Code, http.StatusBadRequest)
	}
}

func TestClientControllerCreateClientProfile_Duplicate(t *testing.T) {
	ctrl := &clientController{svc: &fakeClientService{createErr: clienterrors.ErrDuplicateClient}}
	req := httptest.NewRequest(http.MethodPost, "/clients", strings.NewReader(`{"first_name":"Alice","last_name":"Smith","date_of_birth":"1990-01-01T00:00:00Z","gender":"female","email":"alice@example.com","phone_number":"+123","address":"1 Main","city":"Seattle","state":"WA","country":"USA","postal_code":"98101","created_by_agent_id":"`+newUUIDString(t)+`"}`))
	res := httptest.NewRecorder()

	ctrl.CreateClientProfile(res, req)
	if res.Code != http.StatusConflict {
		t.Fatalf("status = %d, want %d", res.Code, http.StatusConflict)
	}
}

func TestClientControllerUpdateClientInformation_BadInput(t *testing.T) {
	ctrl := &clientController{svc: &fakeClientService{updateErr: clienterrors.ErrBadInput}}
	req := httptest.NewRequest(http.MethodPut, "/clients?id=123", strings.NewReader("{bad json"))
	res := httptest.NewRecorder()

	ctrl.UpdateClientInformation(res, req)
	if res.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want %d", res.Code, http.StatusBadRequest)
	}
}

func TestClientControllerGetClientProfile_ReturnsUser(t *testing.T) {
	clientID := uuid.NewV4()
	want := &models.Client{ClientID: clientID, FirstName: "Alice", LastName: "Smith", DateOfBirth: time.Now().UTC(), Gender: "female", Email: "alice@example.com"}
	ctrl := &clientController{svc: &fakeClientService{getClient: want}}
	req := httptest.NewRequest(http.MethodGet, "/clients?id="+clientID.String(), nil)
	res := httptest.NewRecorder()

	ctrl.GetClientProfile(res, req)
	if res.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", res.Code, http.StatusOK)
	}
	if !strings.Contains(res.Body.String(), "Alice") {
		t.Fatalf("body = %q, want Alice", res.Body.String())
	}
}

func TestClientControllerDeleteClientProfile_NotFound(t *testing.T) {
	ctrl := &clientController{svc: &fakeClientService{deleteErr: clienterrors.ErrClientNotFound}}
	req := httptest.NewRequest(http.MethodDelete, "/clients?id=not-found", nil)
	res := httptest.NewRecorder()

	ctrl.DeleteClientProfile(res, req)
	if res.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want %d", res.Code, http.StatusNotFound)
	}
}

func TestClientControllerVerifyClientIdentity_Success(t *testing.T) {
	ctrl := &clientController{svc: &fakeClientService{}}
	req := httptest.NewRequest(http.MethodGet, "/clients/verify?id="+newUUIDString(t), nil)
	res := httptest.NewRecorder()

	ctrl.VerifyClientIdentity(res, req)
	if res.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", res.Code, http.StatusOK)
	}
}

func TestClientControllerCreateClientProfile_Success(t *testing.T) {
	ctrl := &clientController{svc: &fakeClientService{}}
	body := `{"first_name":"Alice","last_name":"Smith","date_of_birth":"1990-01-01T00:00:00Z","gender":"female","email":"alice@example.com","phone_number":"+123","address":"1 Main","city":"Seattle","state":"WA","country":"USA","postal_code":"98101","created_by_agent_id":"` + newUUIDString(t) + `"}`
	req := httptest.NewRequest(http.MethodPost, "/clients", strings.NewReader(body))
	res := httptest.NewRecorder()

	ctrl.CreateClientProfile(res, req)
	if res.Code != http.StatusCreated {
		t.Fatalf("status = %d, want %d", res.Code, http.StatusCreated)
	}
}
