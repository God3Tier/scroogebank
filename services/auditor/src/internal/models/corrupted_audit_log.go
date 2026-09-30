package models

import "time"

type CorruptedAuditLog struct {
	ID           string    `json:"id"`
	Payload      AuditLog  `json:"payload"`
	DateOfDetect time.Time `json:"date_of_detect"`
}
