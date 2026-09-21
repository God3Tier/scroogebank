package kafka

import (
	"context"
	"encoding/json"
	"log"
	"time"

	"github.com/google/uuid"
	"github.com/segmentio/kafka-go"
)

func InitKafkaWriter(topic string, serverAddr string) *kafka.Writer {
	return &kafka.Writer{
		Addr:     kafka.TCP(serverAddr),
		Topic:    topic,
		Balancer: &kafka.LeastBytes{},
	}
}

type RejectedEvent struct {
	ID             string
	KafkaTopic     string
	KafkaOffset    int64
	KafkaPartition int
	RawPayload     string // the raw bytes, so you can inspect what actually broke
	Reason         string
	RejectedAt     time.Time
}

func RunKafkaWriter(ctx context.Context, kafkaWriter *kafka.Writer, queue <-chan Rejection) {
	for {
		select {
		case <-ctx.Done():
			return
		case rejection, ok := <-queue:
			if !ok {
				return 
			}
			{
				rejectionEvent := RejectedEvent{
					ID:             uuid.NewString(),
					KafkaTopic:     rejection.msg.Topic,
					KafkaOffset:    rejection.msg.Offset,
					KafkaPartition: rejection.msg.Partition,
					RawPayload:     string(rejection.msg.Value),
					Reason:         rejection.reason,
					RejectedAt:     time.Now().UTC(),
				}

				payload, err := json.Marshal(rejectionEvent)
				if err != nil {
					log.Printf(
						"CRITICAL: failed to marshal rejection, offset=%d: %v",
						rejection.msg.Offset,
						err,
					)
					return
				}

				err = kafkaWriter.WriteMessages(context.Background(), kafka.Message{
					Key:   rejection.msg.Key,
					Value: payload,
				})

				if err != nil {
					log.Printf(
						"CRITICAL: failed to publish rejection to DLQ, offset=%d: %v",
						rejection.msg.Offset,
						err,
					)
					return
				}

				log.Printf(
					"rejected event published to DLQ, topic=%s offset=%d reason=%s",
					rejection.msg.Topic,
					rejection.msg.Offset,
					rejection.reason,
				)
			}
		}
	}

}
