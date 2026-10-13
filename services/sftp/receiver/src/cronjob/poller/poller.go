package poller

import (
	"context"
	"errors"
	"fmt"
	"log"
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

// Poller reads transaction report files from the configured SFTP directory.
type Poller struct {
	config    Config
	store     transactionStore
	accounts  accountDirectory
	audit     *Audit // nil when auditing is off
	connector remoteConnector
	now       func() time.Time

	// failures holds the failures of the current episodes, so a failure that
	// repeats on every poll is audited once per episode (see recordFailure).
	// Polls run one at a time, so no lock is needed.
	failures map[failureKey]*failureEpisode
}

func New(cfg Config, store transactionStore, accounts accountDirectory, audit ...Audit) *Poller {
	p := &Poller{config: cfg, store: store, accounts: accounts, now: time.Now, failures: make(map[failureKey]*failureEpisode)}
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
	defer p.relayAuditEvents(ctx)

	client, closeClient, err := p.connector(ctx)
	if err != nil {
		return errors.Join(err, p.recordFailure(ctx, failureKey{kind: connectionFailure}, err))
	}
	defer closeClient()

	entries, err := client.ReadDir(p.config.RemoteDir)
	if err != nil {
		wrapped := fmt.Errorf("list remote directory %q: %w", p.config.RemoteDir, err)
		return errors.Join(wrapped, p.recordFailure(ctx, failureKey{kind: connectionFailure}, wrapped))
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].Name() < entries[j].Name() })
	var waiting []string
	for _, entry := range entries {
		if entry.Mode().IsRegular() && strings.HasSuffix(strings.ToLower(entry.Name()), ".json") {
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
