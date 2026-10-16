package poller

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// resilientPoller connects through connect, records each wait between
// attempts in waits, and reads its time from *now.
func resilientPoller(connect remoteConnector, now *time.Time, waits *[]time.Duration, events *fakeEvents, relay *fakeRelay) *Poller {
	p := New(Config{RemoteDir: "/reports"}, &fakeStore{}, &fakeDirectory{}, Audit{Events: events, Relay: relay})
	p.connector = connect
	p.now = func() time.Time { return *now }
	p.sleep = func(_ context.Context, d time.Duration) error {
		*waits = append(*waits, d)
		return nil
	}
	return p
}

func TestPollRetriesAFailedConnection(t *testing.T) {
	refused := errors.New("connection refused")
	tests := map[string]struct {
		failures     int
		err          error
		wantAttempts int
		wantWaits    int
		wantImported bool
	}{
		"connects on the third attempt": {failures: 2, err: refused, wantAttempts: 3, wantWaits: 2, wantImported: true},
		"gives up after three attempts": {failures: 5, err: refused, wantAttempts: 3, wantWaits: 2},
		"a host-key mismatch is not retried": {
			failures: 5, err: permanentError{errors.New("SFTP host key fingerprint mismatch")}, wantAttempts: 1,
		},
	}

	for name, tt := range tests {
		t.Run(name, func(t *testing.T) {
			remote := newFakeRemote()
			remote.add("a.json", validJSON)
			attempts := 0
			connect := func(context.Context) (remoteSFTP, func(), error) {
				attempts++
				if attempts <= tt.failures {
					return nil, nil, tt.err
				}
				return remote, func() {}, nil
			}
			now := time.Date(2026, 10, 14, 8, 0, 0, 0, time.UTC)
			var waits []time.Duration
			events := &fakeEvents{}

			err := resilientPoller(connect, &now, &waits, events, &fakeRelay{}).Poll(context.Background())

			assert.Equal(t, tt.wantAttempts, attempts)
			require.Len(t, waits, tt.wantWaits)
			for i, wait := range waits {
				base := connectBackoff << i
				assert.GreaterOrEqual(t, wait, base, "wait %d", i)
				assert.Less(t, wait, base+connectJitter, "wait %d", i)
			}
			if tt.wantImported {
				require.NoError(t, err)
				assert.Len(t, remote.renamed, 1)
				assert.Empty(t, events.failures, "a connection that succeeds on a retry is not a failure")
				return
			}
			require.ErrorIs(t, err, tt.err)
			require.Len(t, events.failures, 1, "only the last attempt's error is audited")
			assert.Equal(t, "CONNECTION_FAILURE", events.failures[0].Action)
		})
	}
}

func TestPollReconnectsAfterAListingFailure(t *testing.T) {
	remote := newFakeRemote()
	remote.add("a.json", validJSON)
	remote.readDirFailures = 1
	connections, closed := 0, 0
	connect := func(context.Context) (remoteSFTP, func(), error) {
		connections++
		return remote, func() { closed++ }, nil
	}
	now := time.Date(2026, 10, 14, 8, 0, 0, 0, time.UTC)
	var waits []time.Duration

	require.NoError(t, resilientPoller(connect, &now, &waits, &fakeEvents{}, &fakeRelay{}).Poll(context.Background()))

	assert.Equal(t, 2, connections)
	assert.Equal(t, 2, closed, "the failed connection is closed, and so is the one the poll used")
	assert.Len(t, remote.renamed, 1)
}

func TestPollDoesNotWaitToRetryPastItsReserve(t *testing.T) {
	attempts := 0
	connect := func(context.Context) (remoteSFTP, func(), error) {
		attempts++
		return nil, nil, errors.New("connection refused")
	}
	now := time.Date(2026, 10, 14, 8, 0, 0, 0, time.UTC)
	var waits []time.Duration
	// After the first wait, less than runDeadlineReserve would be left.
	ctx, cancel := context.WithTimeout(context.Background(), runDeadlineReserve+connectBackoff)
	defer cancel()

	require.Error(t, resilientPoller(connect, &now, &waits, &fakeEvents{}, &fakeRelay{}).Poll(ctx))

	assert.Equal(t, 1, attempts)
	assert.Empty(t, waits)
}

func TestPollOpensTheCircuitAfterRepeatedFailures(t *testing.T) {
	ctx := context.Background()
	remote := newFakeRemote()
	remote.add("a.json", validJSON)
	down := errors.New("connection refused")
	attempts := 0
	connect := func(context.Context) (remoteSFTP, func(), error) {
		attempts++
		if down != nil {
			return nil, nil, down
		}
		return remote, func() {}, nil
	}
	now := time.Date(2026, 10, 14, 8, 0, 0, 0, time.UTC)
	var waits []time.Duration
	events := &fakeEvents{}
	relay := &fakeRelay{}
	p := resilientPoller(connect, &now, &waits, events, relay)

	for range breakerThreshold {
		require.Error(t, p.Poll(ctx))
	}
	require.Equal(t, breakerThreshold*connectAttempts, attempts)

	// Open: a poll does not connect, but still relays the audit events.
	err := p.Poll(ctx)
	require.ErrorIs(t, err, down)
	assert.Contains(t, err.Error(), "SFTP circuit open until")
	assert.Equal(t, breakerThreshold*connectAttempts, attempts, "an open circuit does not connect")
	assert.Equal(t, breakerThreshold+1, relay.runs)
	assert.Len(t, events.failures, 1, "the outage is audited once")

	// Half-open once the open period has passed: one poll tries, fails and
	// opens the circuit again.
	now = now.Add(breakerCooldown)
	require.Error(t, p.Poll(ctx))
	assert.Equal(t, (breakerThreshold+1)*connectAttempts, attempts)
	require.ErrorContains(t, p.Poll(ctx), "SFTP circuit open until")
	assert.Equal(t, (breakerThreshold+1)*connectAttempts, attempts)

	// The server is back: the next half-open poll connects, which closes the
	// circuit and ends the outage, so a later failure is a new outage.
	down = nil
	now = now.Add(breakerCooldown)
	require.NoError(t, p.Poll(ctx))
	assert.Len(t, remote.renamed, 1)
	down = errors.New("connection refused")
	require.Error(t, p.Poll(ctx))
	assert.Len(t, events.failures, 2)
}

// hangingRemote is a remote whose Open waits until the connection is closed,
// like an SFTP call on a connection that stopped answering.
type hangingRemote struct {
	*fakeRemote
	opened, closed chan struct{}
}

func (r *hangingRemote) Open(string) (remoteFile, error) {
	close(r.opened)
	select {
	case <-r.closed:
		return nil, errors.New("connection closed")
	case <-time.After(5 * time.Second):
		return nil, errors.New("the connection was not closed")
	}
}

func TestPollClosesAHungConnectionWhenItsContextEnds(t *testing.T) {
	remote := newFakeRemote()
	remote.add("a.json", validJSON)
	hung := &hangingRemote{fakeRemote: remote, opened: make(chan struct{}), closed: make(chan struct{})}
	p := New(Config{RemoteDir: "/reports"}, &fakeStore{}, &fakeDirectory{})
	// Closing twice would panic, so this also checks it is closed only once.
	p.connector = func(context.Context) (remoteSFTP, func(), error) { return hung, func() { close(hung.closed) }, nil }
	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		<-hung.opened
		cancel()
	}()

	err := p.Poll(ctx)

	require.Error(t, err)
	assert.Contains(t, err.Error(), "connection closed")
}
