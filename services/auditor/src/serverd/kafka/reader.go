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

func RunKafkaReader(ctx context.Context, kafkaReader *kafka.Reader, repo repository.Registry, rejectionQueue chan Rejection) {
	for {
		msg, err := kafkaReader.ReadMessage(ctx)
		if err != nil {
			continue
		}

		var event ValidEvent
		if err := json.Unmarshal(msg.Value, &event); err != nil {
			rejectionQueue <- Rejection{
				msg,
				"unparseable payload: " + err.Error(),
			}
			continue
		}

		if err := validatePayload(event); err != nil {
			rejectionQueue <- Rejection{
				msg,
				fmt.Sprintf(
					"unauthorized: %s not permitted to send %s",
					event.SourceService, event.EventType,
				),
			}
			continue
		}

		parsedEvent := models.AuditLogInput{
			EventType:     event.EventType,
			Action:        event.Action,
			SourceService: models.ServiceType(event.SourceService),
			ActorId:       event.ActorId,
			Payload:       string(event.Payload),
		}

		repo.GetAudit().StoreAuditChain(ctx, parsedEvent) // only valid, authorized events make it into the actual chain
	}
}
