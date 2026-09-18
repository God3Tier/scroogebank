package kafka

import (
	"auditor/src/internal/models"
	"auditor/src/internal/repository"
	"context"
	"encoding/json"
	"fmt"

	"github.com/segmentio/kafka-go"
)

func InitKafkaReader(topic string, kafkaBrokers []string) *kafka.Reader {
	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers:  kafkaBrokers, // "kafka:29092" if this runs inside the docker network
		Topic:    topic,
		GroupID:  "audit-service", // enables committed offsets + consumer groups
		MinBytes: 10e3,
		MaxBytes: 10e6,
	})
	return reader
}

type ValidEvent struct {
	EventType     string       `json:"event_type"`
	Action        string       `json:"action"`
	SourceService string       `json:"source_service"`
	ActorId       string       `json:"actor_id"`
	Payload       ValidPayload `json:"payload"`
}

type ValidPayload struct {
	AttributeName string `json:"attribute_name"`
	BeforeValue   string `json:"before_value"`
	AfterValue    string `json:"after_value"`
	AgentID       string `json:"agent_id"`
	ClientID      string `json:"client_id"`
	DateTime      string `json:"datetime"`
}

func RunKafkaReader(ctx context.Context, kafkaReader *kafka.Reader, repo repository.Registry, rejectionQueue chan Rejection) {
	for {
		msg, err := kafkaReader.ReadMessage(ctx)
		if err != nil {
			continue
		}

		var event models.AuditLogInput
		if err := json.Unmarshal(msg.Value, &event); err != nil {
			rejectionQueue <- Rejection{
				msg,
				"unparseable payload: " + err.Error(),
			}
			continue
		}

		if !event.SourceService.IsValidAction(event.EventType) {
			rejectionQueue <- Rejection{
				msg,
				fmt.Sprintf(
					"unauthorized: %s not permitted to send %s",
					event.SourceService, event.EventType,
				),
			}
			continue
		}

		repo.GetAudit().StoreAuditChain(ctx, event) // only valid, authorized events make it into the actual chain
	}
}
