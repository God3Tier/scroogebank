package poller

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"path"

	"receiver/src/internal/models"
)

func (p *Poller) processFile(ctx context.Context, client remoteSFTP, remotePath string) error {
	remoteFile, err := client.Open(remotePath)
	if err != nil {
		return fmt.Errorf("open remote file: %w", err)
	}
	contents, readErr := io.ReadAll(remoteFile)
	closeErr := remoteFile.Close()
	if readErr != nil {
		return fmt.Errorf("read remote file: %w", readErr)
	}
	if closeErr != nil {
		return fmt.Errorf("close remote file: %w", closeErr)
	}

	var transactions []models.Transaction
	if err := json.Unmarshal(contents, &transactions); err != nil {
		return fmt.Errorf("parse JSON transaction file: %w", err)
	}
	if transactions == nil {
		return fmt.Errorf("parse JSON transaction file: expected an array")
	}

	accepted, rejected := 0, 0
	for index, transaction := range transactions {
		if err := normalizeTransaction(&transaction); err != nil {
			rejected++
			log.Printf("rejecting transaction %d in %s: %v", index, path.Base(remotePath), err)
			continue
		}
		if err := p.store.SaveTransaction(ctx, transaction); err != nil {
			return fmt.Errorf("save transaction %q: %w", transaction.TransactionID, err)
		}
		accepted++
	}

	archiveStatus := "processed"
	if rejected > 0 {
		archiveStatus = "rejected"
	}
	archiveDir := path.Join(p.config.RemoteDir, archiveStatus)
	if err := client.MkdirAll(archiveDir); err != nil {
		return fmt.Errorf("create %s directory: %w", archiveStatus, err)
	}
	if err := client.Rename(remotePath, path.Join(archiveDir, path.Base(remotePath))); err != nil {
		return fmt.Errorf("archive processed file: %w", err)
	}
	log.Printf("processed %d transactions from %s (%d accepted, %d rejected)", len(transactions), path.Base(remotePath), accepted, rejected)
	return nil
}
