package poller

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"receiver/src/internal/models"
)

// episodePoller audits into events and reads its time from a clock that moves
// one minute on every reading.
func episodePoller(remote *fakeRemote, store *fakeStore, directory *fakeDirectory, relay *fakeRelay, events *fakeEvents) *Poller {
	p := New(Config{RemoteDir: "/reports"}, store, directory, Audit{Events: events, Relay: relay})
	p.connector = func(context.Context) (remoteSFTP, func(), error) { return remote, func() {}, nil }
	p.sleep = noSleep
	clock := time.Date(2026, 10, 13, 8, 0, 0, 0, time.UTC)
	p.now = func() time.Time {
		clock = clock.Add(time.Minute)
		return clock
	}
	return p
}

func TestPollAuditsAConnectionFailureOncePerOutage(t *testing.T) {
	ctx := context.Background()
	events := &fakeEvents{}
	remote := newFakeRemote()
	p := episodePoller(remote, &fakeStore{}, &fakeDirectory{}, &fakeRelay{}, events)
	var down error
	connect := p.connector
	p.connector = func(ctx context.Context) (remoteSFTP, func(), error) {
		if down != nil {
			return nil, nil, down
		}
		return connect(ctx)
	}

	down = errors.New("connection refused")
	for range 3 {
		require.Error(t, p.Poll(ctx))
	}
	require.Len(t, events.failures, 1, "an outage is audited once, not on every poll")
	first := events.failures[0]
	assert.Equal(t, "CONNECTION_FAILURE", first.Action)
	assert.Empty(t, first.FileName)

	// The server is back, then fails the same way again: a new outage. The
	// three failed polls opened the circuit, so let its open period pass.
	clock := p.now
	p.now = func() time.Time { return clock().Add(breakerCooldown) }
	down = nil
	require.NoError(t, p.Poll(ctx))
	down = errors.New("connection refused")
	require.Error(t, p.Poll(ctx))

	require.Len(t, events.failures, 2, "a later outage with the same error text is audited too")
	assert.Equal(t, first.Error, events.failures[1].Error)
	assert.True(t, events.failures[1].OccurredAt.After(first.OccurredAt),
		"each outage has its own time, and so its own event ID")
}

func TestPollTreatsAListingFailureAsPartOfTheConnectionOutage(t *testing.T) {
	ctx := context.Background()
	events := &fakeEvents{}
	remote := newFakeRemote()
	p := episodePoller(remote, &fakeStore{}, &fakeDirectory{}, &fakeRelay{}, events)

	remote.readDirErr = errors.New("permission denied")
	for range 2 {
		require.Error(t, p.Poll(ctx))
	}

	require.Len(t, events.failures, 1)
	assert.Equal(t, "CONNECTION_FAILURE", events.failures[0].Action)
	assert.Contains(t, events.failures[0].Error, "list remote directory")
}

func TestPollRetriesAFailureEventThatCouldNotBeWritten(t *testing.T) {
	ctx := context.Background()
	events := &fakeEvents{}
	store := &fakeStore{auditErr: errors.New("database unavailable")}
	relay := &fakeRelay{sendErr: errors.New("SQS unavailable")}
	p := episodePoller(newFakeRemote(), store, &fakeDirectory{}, relay, events)
	p.connector = func(context.Context) (remoteSFTP, func(), error) {
		return nil, nil, errors.New("connection refused")
	}

	err := p.Poll(ctx)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "SQS unavailable", "the unwritten event is reported")

	store.auditErr = nil
	require.Error(t, p.Poll(ctx))
	require.Len(t, events.failures, 2, "the event is built again on the next poll")
	assert.Equal(t, events.failures[0].OccurredAt, events.failures[1].OccurredAt,
		"the retry keeps the episode's time, and so its event ID")
	assert.Len(t, store.soloEvents, 1)

	require.Error(t, p.Poll(ctx))
	assert.Len(t, events.failures, 2, "once written, the episode is not audited again")
}

func TestPollAuditsAFileFailureOnceWhileTheFileWaits(t *testing.T) {
	ctx := context.Background()
	events := &fakeEvents{}
	remote := newFakeRemote()
	p := episodePoller(remote, &fakeStore{}, &fakeDirectory{}, &fakeRelay{}, events)
	remote.add("stuck.json", validJSON)
	remote.openErr = errors.New("file unavailable")

	for range 3 {
		require.Error(t, p.Poll(ctx))
	}
	require.Len(t, events.failures, 1)
	assert.Equal(t, "stuck.json", events.failures[0].FileName)
	assert.Equal(t, "SYSTEM_EXCEPTION", events.failures[0].Action)

	// The file is removed by hand, which ends its episode; when it is put
	// back and fails again, that is a new episode.
	delete(remote.files, "/reports/stuck.json")
	require.NoError(t, p.Poll(ctx))
	remote.add("stuck.json", validJSON)
	require.Error(t, p.Poll(ctx))

	require.Len(t, events.failures, 2)
	assert.True(t, events.failures[1].OccurredAt.After(events.failures[0].OccurredAt))
}

func TestPollAuditsEachKindOfFailureOnAFile(t *testing.T) {
	ctx := context.Background()
	events := &fakeEvents{}
	remote := newFakeRemote()
	store := &fakeStore{}
	directory := &fakeDirectory{err: errors.New("account-service unavailable")}
	p := episodePoller(remote, store, directory, &fakeRelay{}, events)
	remote.add("link.json", linkJSON)

	require.Error(t, p.Poll(ctx))
	// account-service is back, but the import cannot be saved.
	directory.err = nil
	directory.accounts = map[string]models.Account{knownAccount: sgdAccount}
	store.err = errors.New("database unavailable")
	require.Error(t, p.Poll(ctx))

	require.Len(t, events.failures, 2)
	assert.Contains(t, events.failures[0].Error, "look up accounts")
	assert.Contains(t, events.failures[1].Error, "save transactions")
}
