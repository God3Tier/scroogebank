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
	connector remoteConnector
}

func New(cfg Config, store transactionStore) *Poller {
	p := &Poller{config: cfg, store: store}
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
		return err
	}
	defer closeClient()

	entries, err := client.ReadDir(p.config.RemoteDir)
	if err != nil {
		return fmt.Errorf("list remote directory %q: %w", p.config.RemoteDir, err)
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
