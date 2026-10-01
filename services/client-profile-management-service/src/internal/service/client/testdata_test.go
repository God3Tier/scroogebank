package client

import (
	"time"
	"uuid"

	"client-profile-management-service/src/internal/models"
)

func validClient() models.Client {
	return models.Client{
		ClientID:         uuid.New(),
		FirstName:        "Alice",
		LastName:         "Smith",
		DateOfBirth:      time.Date(1990, 5, 10, 0, 0, 0, 0, time.UTC),
		Gender:           "female",
		Email:            "alice@example.test",
		PhoneNumber:      "+6561234567",
		Address:          "1 Example Street",
		City:             "Singapore",
		State:            "Singapore",
		Country:          "Singapore",
		PostalCode:       "123456",
		CreatedByAgentID: "agent-123",
	}
}
