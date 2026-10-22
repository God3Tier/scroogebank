package client

import (
	"client-profile-management-service/src/internal/models"
	service "client-profile-management-service/src/internal/service/client"
	"net/http"
)

type ClientController interface {
	CreateClientProfile(w http.ResponseWriter, r *http.Request)
	VerifyClientIdentity(w http.ResponseWriter, r *http.Request)
	UpdateClientInformation(w http.ResponseWriter, r *http.Request)
	GetClientProfile(w http.ResponseWriter, r *http.Request)
	ListClients(w http.ResponseWriter, r *http.Request, caller models.Caller)
	DeleteClientProfile(w http.ResponseWriter, r *http.Request)
	// Identity documents (ADR-0041)
	UploadClientDocument(w http.ResponseWriter, r *http.Request)
	ListClientDocuments(w http.ResponseWriter, r *http.Request)
	GetClientDocument(w http.ResponseWriter, r *http.Request)
}

type clientController struct {
	svc service.ClientService
}

func New(svc service.ClientService) ClientController {
	return clientController{
		svc: svc,
	}
}
