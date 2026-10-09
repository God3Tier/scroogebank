package queue

// Rejection describes an SQS message that failed validation and needs to be
// sent to the dead-letter queue, along with enough of the original message
// to diagnose why.
type Rejection struct {
	MessageID     string
	ReceiptHandle string
	Body          string
	ReceiveCount  int
	Reason        string
}
