package poller

import "time"

const (
	// breakerThreshold is how many polls in a row must fail to connect, each
	// after its retries, before the circuit opens.
	breakerThreshold = 3
	// breakerCooldown is how long the circuit stays open. After it, one poll
	// tries to connect again.
	breakerCooldown = 15 * time.Minute
)

// breaker stops polls from connecting to an SFTP server that keeps failing
// (ADR-0024 decision 3). Its state is kept in memory, like failure episodes:
// a restarted poller starts with the circuit closed. Polls run one at a
// time, so it needs no lock.
type breaker struct {
	failures  int       // polls in a row that could not connect
	openUntil time.Time // while open, polls do not connect
	lastErr   error     // why the last poll could not connect
}

// allow reports whether a poll may connect at now. Once the open period has
// passed, a poll may try again (half-open).
func (b *breaker) allow(now time.Time) bool {
	return !now.Before(b.openUntil)
}

// succeeded closes the circuit: a poll connected and listed the directory.
func (b *breaker) succeeded() {
	*b = breaker{}
}

// failed records a poll that could not connect. The circuit opens on the
// breakerThreshold-th failure in a row, and opens again when a half-open
// poll fails.
func (b *breaker) failed(now time.Time, err error) {
	b.failures++
	b.lastErr = err
	if b.failures >= breakerThreshold {
		b.openUntil = now.Add(breakerCooldown)
	}
}
