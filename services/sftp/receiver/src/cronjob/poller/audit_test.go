package poller

import (
	"context"
	"errors"
	"testing"

	"receiver/src/internal/models"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// fakeEvents records what the poller asked to audit. Each event's ID names
// its kind and file.
type fakeEvents struct {
	completed []FileCompleted
	failures  []ProcessingError
}

func (a *fakeEvents) FileCompleted(item FileCompleted) (models.AuditEvent, error) {
	a.completed = append(a.completed, item)
	return models.AuditEvent{ID: "FILE_PROCESSING_COMPLETED:" + item.FileName}, nil
}

func (a *fakeEvents) ProcessingError(item ProcessingError) (models.AuditEvent, error) {
	a.failures = append(a.failures, item)
	return models.AuditEvent{ID: item.Action + ":" + item.FileName}, nil
}

type fakeRelay struct {
	runs    int
	sent    []models.AuditEvent
	sendErr error
}

func (r *fakeRelay) RelayOnce(context.Context) error {
	r.runs++
	return nil
}

func (r *fakeRelay) Send(_ context.Context, event models.AuditEvent) error {
	if r.sendErr != nil {
		return r.sendErr
	}
	r.sent = append(r.sent, event)
	return nil
}

func auditedPoller(remote *fakeRemote, store *fakeStore, relay *fakeRelay) *Poller {
	p := New(Config{RemoteDir: "/reports"}, store, &fakeDirectory{}, Audit{Events: &fakeEvents{}, Relay: relay})
	p.connector = func(context.Context) (remoteSFTP, func(), error) { return remote, func() {}, nil }
	return p
}

func eventIDs(events []models.AuditEvent) []string {
	ids := make([]string, 0, len(events))
	for _, event := range events {
		ids = append(ids, event.ID)
	}
	return ids
}

func TestPollSavesTheFileEventWithItsTransactions(t *testing.T) {
	remote := newFakeRemote()
	remote.add("valid.json", validJSON)
	store := &fakeStore{}
	relay := &fakeRelay{}

	require.NoError(t, auditedPoller(remote, store, relay).Poll(context.Background()))

	assert.Len(t, store.saved, 1)
	assert.Equal(t, []string{"FILE_PROCESSING_COMPLETED:valid.json"}, eventIDs(store.importEvents),
		"the file's event is saved in the same import as its transactions")
	assert.Empty(t, store.soloEvents)
	assert.Equal(t, 1, relay.runs, "the relay runs at the end of the poll")
}

func TestPollFailedImportSavesOnlyTheFailureEvent(t *testing.T) {
	remote := newFakeRemote()
	remote.add("retry.json", validJSON)
	store := &fakeStore{err: errors.New("database unavailable")}

	require.Error(t, auditedPoller(remote, store, &fakeRelay{}).Poll(context.Background()))

	assert.Empty(t, store.importEvents, "no completion event without the import")
	assert.Equal(t, []string{"SYSTEM_EXCEPTION:retry.json"}, eventIDs(store.soloEvents))
}

func TestPollSendsAnUnsavableFailureEventStraightToSQS(t *testing.T) {
	remote := newFakeRemote()
	remote.add("retry.json", validJSON)
	dbErr := errors.New("database unavailable")
	store := &fakeStore{err: dbErr, auditErr: dbErr}
	relay := &fakeRelay{}

	err := auditedPoller(remote, store, relay).Poll(context.Background())

	require.ErrorIs(t, err, dbErr)
	assert.Empty(t, store.soloEvents)
	assert.Equal(t, []string{"SYSTEM_EXCEPTION:retry.json"}, eventIDs(relay.sent))
}

func TestPollReportsAFailureEventThatCannotBeSavedOrSent(t *testing.T) {
	remote := newFakeRemote()
	remote.add("retry.json", validJSON)
	dbErr := errors.New("database unavailable")
	sqsErr := errors.New("SQS unavailable")

	err := auditedPoller(remote, &fakeStore{err: dbErr, auditErr: dbErr}, &fakeRelay{sendErr: sqsErr}).Poll(context.Background())

	require.ErrorIs(t, err, dbErr)
	assert.Contains(t, err.Error(), sqsErr.Error(), "the lost audit event is reported with the failure")
}

func TestPollRelaysEvenWhenTheConnectionFails(t *testing.T) {
	store := &fakeStore{}
	relay := &fakeRelay{}
	p := auditedPoller(newFakeRemote(), store, relay)
	p.connector = func(context.Context) (remoteSFTP, func(), error) {
		return nil, nil, errors.New("connection refused")
	}

	require.Error(t, p.Poll(context.Background()))

	assert.Equal(t, []string{"CONNECTION_FAILURE:"}, eventIDs(store.soloEvents))
	assert.Equal(t, 1, relay.runs)
}
