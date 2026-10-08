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

type transactionStore interface {
	SaveTransaction(context.Context, models.Transaction) error
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

// Poller reads transaction report files from the configured SFTP directory.
type Poller struct {
	config    Config
	store     transactionStore
	accounts  accountDirectory
	audit     AuditWriter
	connector remoteConnector

	// lookupFailureAudited holds files whose account lookup failure has been
	// audited. Such a file is retried on every poll, so only the first failure
	// of each outage is audited. Polls run one at a time, so no lock is needed.
	lookupFailureAudited map[string]bool
}

func New(cfg Config, store transactionStore, accounts accountDirectory, auditWriters ...AuditWriter) *Poller {
	p := &Poller{config: cfg, store: store, accounts: accounts, lookupFailureAudited: make(map[string]bool)}
	if len(auditWriters) > 0 {
		p.audit = auditWriters[0]
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
	client, closeClient, err := p.connector(ctx)
	if err != nil {
		return errors.Join(err, p.writeProcessingError(ctx, ProcessingError{Action: "CONNECTION_FAILURE", Stage: "CONNECTION", Error: err.Error()}))
	}
	defer closeClient()

	entries, err := client.ReadDir(p.config.RemoteDir)
	if err != nil {
		wrapped := fmt.Errorf("list remote directory %q: %w", p.config.RemoteDir, err)
		return errors.Join(wrapped, p.writeProcessingError(ctx, ProcessingError{Action: "CONNECTION_FAILURE", Stage: "CONNECTION", Error: wrapped.Error()}))
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].Name() < entries[j].Name() })

	var failures []error
	for _, entry := range entries {
		if !entry.Mode().IsRegular() || !strings.HasSuffix(strings.ToLower(entry.Name()), ".json") {
			continue
		}
		if err := p.processFile(ctx, client, path.Join(p.config.RemoteDir, entry.Name())); err != nil {
			failures = append(failures, fmt.Errorf("process %s: %w", entry.Name(), err))
		}
	}
	return errors.Join(failures...)
}

func (p *Poller) writeProcessingError(ctx context.Context, item ProcessingError) error {
	if p.audit == nil {
		return nil
	}
	return p.audit.WriteProcessingError(ctx, item)
}
