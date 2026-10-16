package poller

import (
	"context"
	"errors"
	"fmt"
	"log"
	"math/rand/v2"
	"os"
	"path"
	"sort"
	"strings"
	"time"

	"receiver/src/internal/models"
)

// transactionStore saves each file's transactions together with its audit
// event, and audit events that have no import to join (ADR-0020 decision 3).
type transactionStore interface {
	SaveImport(context.Context, []models.Transaction, *models.AuditEvent) error
	SaveAuditEvent(context.Context, models.AuditEvent) error
}

// accountDirectory reports which account references are CRM accounts, with
// each one's client ID (ADR-0037).
type accountDirectory interface {
	Lookup(ctx context.Context, accountIDs []string) (map[string]string, error)
}

type remoteFile interface {
	Read([]byte) (int, error)
	Close() error
}

type remoteSFTP interface {
	ReadDir(string) ([]os.FileInfo, error)
	Open(string) (remoteFile, error)
	MkdirAll(string) error
	Rename(string, string) error
}

type remoteConnector func(context.Context) (remoteSFTP, func(), error)

// runDeadlineReserve is how much time a poll with a deadline, such as a
// Lambda invocation, must have left to start another file. It covers that
// file and relaying the poll's audit events; files not started wait for the
// next poll (ADR-0024 decision 3).
const runDeadlineReserve = time.Minute

const (
	// pollTimeout bounds a whole poll. When it ends, the connection is
	// closed, which ends any SFTP call still waiting: those calls take no
	// context. A poll whose context ends sooner, such as a Lambda
	// invocation's, ends then.
	pollTimeout = 10 * time.Minute
	// connectAttempts is how many times a poll tries to connect and list the
	// remote directory. It waits connectBackoff before the second attempt,
	// twice that before the third, and up to connectJitter more each time.
	connectAttempts = 3
	connectBackoff  = 2 * time.Second
	connectJitter   = time.Second
)

// Poller reads transaction report files from the configured SFTP directory.
type Poller struct {
	config    Config
	store     transactionStore
	accounts  accountDirectory
	audit     *Audit // nil when auditing is off
	connector remoteConnector
	now       func() time.Time
	sleep     func(context.Context, time.Duration) error

	// failures holds the failures of the current episodes, so a failure that
	// repeats on every poll is audited once per episode (see recordFailure).
	// Polls run one at a time, so no lock is needed.
	failures map[failureKey]*failureEpisode
	breaker  breaker
}

func New(cfg Config, store transactionStore, accounts accountDirectory, audit ...Audit) *Poller {
	p := &Poller{config: cfg, store: store, accounts: accounts, now: time.Now, sleep: sleepContext, failures: make(map[failureKey]*failureEpisode)}
	if len(audit) > 0 {
		p.audit = &audit[0]
	}
	p.connector = p.connectSFTP
	return p
}

func (p *Poller) Run(ctx context.Context, interval time.Duration) error {
	pollAndLog(ctx, p)
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			pollAndLog(ctx, p)
		}
	}
}

func pollAndLog(ctx context.Context, p *Poller) {
	if err := p.Poll(ctx); err != nil {
		log.Printf("SFTP poll failed: %v", err)
	}
}

func (p *Poller) Poll(ctx context.Context) error {
	// Whatever happens below, send the events this poll saved, and any left
	// from earlier polls.
	// The relay keeps the caller's context, so it still runs after a poll
	// timeout.
	defer p.relayAuditEvents(ctx)
	ctx, cancel := context.WithTimeout(ctx, pollTimeout)
	defer cancel()

	connection := failureKey{kind: connectionFailure}
	if !p.breaker.allow(p.now()) {
		// The outage's event is written already, unless writing it failed:
		// then this tries again.
		err := fmt.Errorf("SFTP circuit open until %s: %w", p.breaker.openUntil.Format(time.RFC3339), p.breaker.lastErr)
		return errors.Join(err, p.recordFailure(ctx, connection, p.breaker.lastErr))
	}
	client, closeClient, entries, err := p.connectAndList(ctx)
	if err != nil {
		p.breaker.failed(p.now(), err)
		return errors.Join(err, p.recordFailure(ctx, connection, err))
	}
	p.breaker.succeeded()
	// Close the connection when the poll's context ends, to end an SFTP call
	// that hangs; otherwise close it when the poll returns.
	stopClosing := context.AfterFunc(ctx, closeClient)
	defer func() {
		if stopClosing() {
			closeClient()
		}
	}()

	sort.Slice(entries, func(i, j int) bool { return entries[i].Name() < entries[j].Name() })
	// Every regular file is waiting, unsupported ones included, so that they
	// are rejected and audited. A .tmp file is still being uploaded (ADR-0024
	// decision 2).
	var waiting []string
	for _, entry := range entries {
		if entry.Mode().IsRegular() && !hasExtension(entry.Name(), ".tmp") {
			waiting = append(waiting, entry.Name())
		}
	}
	p.endListedFailures(waiting)

	var failures []error
	for i, name := range waiting {
		if deadline, ok := ctx.Deadline(); ok && time.Until(deadline) < runDeadlineReserve {
			log.Printf("less than %s of this poll left: leaving %d files for the next poll", runDeadlineReserve, len(waiting)-i)
			break
		}
		if err := p.processFile(ctx, client, path.Join(p.config.RemoteDir, name)); err != nil {
			failures = append(failures, fmt.Errorf("process %s: %w", name, err))
			continue
		}
		p.endFileFailures(name)
	}
	return errors.Join(failures...)
}

// connectAndList connects and lists the remote directory, retrying with
// backoff (ADR-0024 decision 3). It does not retry a permanent error, which
// retrying cannot fix, nor wait so long that the poll could no longer start
// a file.
func (p *Poller) connectAndList(ctx context.Context) (remoteSFTP, func(), []os.FileInfo, error) {
	wait := connectBackoff
	for attempt := 1; ; attempt++ {
		client, closeClient, entries, err := p.tryConnectAndList(ctx)
		if err == nil {
			return client, closeClient, entries, nil
		}
		if attempt == connectAttempts || isPermanent(err) || !canWait(ctx, wait+connectJitter) {
			return nil, nil, nil, err
		}
		delay := wait + rand.N(connectJitter)
		log.Printf("SFTP attempt %d of %d failed, trying again in %s: %v", attempt, connectAttempts, delay.Round(time.Millisecond), err)
		if p.sleep(ctx, delay) != nil {
			return nil, nil, nil, err
		}
		wait *= 2
	}
}

func (p *Poller) tryConnectAndList(ctx context.Context) (remoteSFTP, func(), []os.FileInfo, error) {
	client, closeClient, err := p.connector(ctx)
	if err != nil {
		return nil, nil, nil, err
	}
	entries, err := client.ReadDir(p.config.RemoteDir)
	if err != nil {
		closeClient()
		return nil, nil, nil, fmt.Errorf("list remote directory %q: %w", p.config.RemoteDir, err)
	}
	return client, closeClient, entries, nil
}

// canWait reports whether waiting d still leaves the poll enough time to
// start a file.
func canWait(ctx context.Context, d time.Duration) bool {
	deadline, ok := ctx.Deadline()
	return !ok || time.Until(deadline)-d >= runDeadlineReserve
}

// sleepContext waits for d, or until ctx ends.
func sleepContext(ctx context.Context, d time.Duration) error {
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

// hasExtension reports whether name ends in ext, ignoring case.
func hasExtension(name, ext string) bool {
	return strings.EqualFold(path.Ext(name), ext)
}

// writeProcessingError saves a processing-error event on its own: there is no
// import to join. If it cannot be saved, typically because the database is
// down, it is sent straight to SQS so the failure is still recorded.
func (p *Poller) writeProcessingError(ctx context.Context, item ProcessingError) error {
	if p.audit == nil {
		return nil
	}
	event, err := p.audit.Events.ProcessingError(item)
	if err != nil {
		return fmt.Errorf("build %s audit event: %w", item.Action, err)
	}
	saveErr := p.store.SaveAuditEvent(ctx, event)
	if saveErr == nil {
		return nil
	}
	if sendErr := p.audit.Relay.Send(ctx, event); sendErr != nil {
		return errors.Join(saveErr, sendErr)
	}
	log.Printf("audit event %s sent directly because it could not be saved: %v", event.ID, saveErr)
	return nil
}

// relayAuditEvents sends waiting audit events. A failure is logged, not
// returned: the events stay in the outbox for the next poll.
func (p *Poller) relayAuditEvents(ctx context.Context) {
	if p.audit == nil {
		return
	}
	if err := p.audit.Relay.RelayOnce(ctx); err != nil {
		log.Printf("relay audit events: %v", err)
	}
}
