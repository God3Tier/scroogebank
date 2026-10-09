package sqs

import (
	"encoding/json"
	"testing"
	"time"

	"receiver/src/cronjob/poller"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func decode(t *testing.T, body []byte) (event, map[string]any) {
	t.Helper()
	var decoded event
	require.NoError(t, json.Unmarshal(body, &decoded))
	var payload map[string]any
	require.NoError(t, json.Unmarshal(decoded.Payload, &payload))
	return decoded, payload
}

func TestEvents_FileCompleted(t *testing.T) {
	start := time.Date(2026, 10, 9, 6, 0, 0, 0, time.UTC)
	result := poller.FileCompleted{
		FileName: "txn.json", ProcessingStart: start, ProcessingEnd: start.Add(time.Second), Status: "Partially Successful",
		RecordsProcessed: 3, RecordsAccepted: 2, RecordsRejected: 1,
		ParseFailures: []poller.ParseFailure{{RecordRef: "record 3", Reason: "account ID is required"}},
	}

	built, err := Events{}.FileCompleted(result)
	require.NoError(t, err)

	decoded, payload := decode(t, built.Body)
	assert.Equal(t, built.ID, decoded.EventID, "the outbox row is keyed by the event's own ID")
	assert.Equal(t, transactionEventType, decoded.EventType)
	assert.Equal(t, "FILE_PROCESSING_COMPLETED", decoded.Action)
	assert.Equal(t, transactionSource, decoded.SourceService)
	assert.Equal(t, transactionActor, decoded.ActorID)
	assert.Equal(t, "txn.json", payload["file_name"])
	assert.Equal(t, "Partially Successful", payload["status"])
	assert.EqualValues(t, 2, payload["records_accepted"])

	again, err := Events{}.FileCompleted(result)
	require.NoError(t, err)
	assert.Equal(t, built.ID, again.ID, "the same outcome gets the same event ID")
}

func TestEvents_ProcessingError(t *testing.T) {
	built, err := Events{}.ProcessingError(poller.ProcessingError{
		FileName: "txn.json", Action: "SYSTEM_EXCEPTION", Stage: "SYSTEM_EXCEPTION", Error: "save transactions: database unavailable",
	})
	require.NoError(t, err)

	decoded, payload := decode(t, built.Body)
	assert.Equal(t, built.ID, decoded.EventID)
	assert.Equal(t, "SYSTEM_EXCEPTION", decoded.Action)
	assert.Equal(t, "save transactions: database unavailable", payload["error"])
}
