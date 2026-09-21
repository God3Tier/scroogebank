package kafka

import (
	"github.com/segmentio/kafka-go"
)

type Rejection struct {
	msg kafka.Message
	reason string
}
