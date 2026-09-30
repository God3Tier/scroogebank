package poller

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"path"
	"time"

	"receiver/src/internal/models"
)

func (p *Poller) processFile(ctx context.Context, client remoteSFTP, remotePath string) error {
	processingStart := time.Now().UTC()
	fileName := path.Base(remotePath)
	remoteFile, err := client.Open(remotePath)
	if err != nil {
		return p.processingFailure(ctx, fileName, "SYSTEM_EXCEPTION", "SYSTEM_EXCEPTION", fmt.Errorf("open remote file: %w", err))
	}
	contents, readErr := io.ReadAll(remoteFile)
	closeErr := remoteFile.Close()
	if readErr != nil {
		return p.processingFailure(ctx, fileName, "SYSTEM_EXCEPTION", "SYSTEM_EXCEPTION", fmt.Errorf("read remote file: %w", readErr))
	}
	if closeErr != nil {
		return p.processingFailure(ctx, fileName, "SYSTEM_EXCEPTION", "SYSTEM_EXCEPTION", fmt.Errorf("close remote file: %w", closeErr))
	}

	var records []json.RawMessage
	if err := json.Unmarshal(contents, &records); err != nil {
		return p.processingFailure(ctx, fileName, "FILE_FORMAT_INVALID", "FILE_FORMAT", fmt.Errorf("parse JSON transaction file: %w", err))
	}
	if records == nil {
		return p.processingFailure(ctx, fileName, "FILE_FORMAT_INVALID", "FILE_FORMAT", fmt.Errorf("parse JSON transaction file: expected an array"))
	}

	accepted, rejected := 0, 0
	parseFailures := make([]ParseFailure, 0)
	for index, record := range records {
		var transaction models.Transaction
		if err := json.Unmarshal(record, &transaction); err != nil {
			rejected++
			parseFailures = append(parseFailures, ParseFailure{RecordRef: fmt.Sprintf("record %d", index+1), Reason: fmt.Sprintf("invalid transaction record: %v", err)})
			log.Printf("rejecting transaction %d in %s: invalid transaction record: %v", index, fileName, err)
			continue
		}
		if err := normalizeTransaction(&transaction); err != nil {
			rejected++
			parseFailures = append(parseFailures, ParseFailure{RecordRef: fmt.Sprintf("record %d", index+1), Reason: err.Error()})
			log.Printf("rejecting transaction %d in %s: %v", index, path.Base(remotePath), err)
			continue
		}
		if err := p.store.SaveTransaction(ctx, transaction); err != nil {
			return p.processingFailure(ctx, fileName, "SYSTEM_EXCEPTION", "SYSTEM_EXCEPTION", fmt.Errorf("save transaction: %w", err))
		}
		accepted++
	}

	archiveStatus := "processed"
	if rejected > 0 {
		archiveStatus = "rejected"
	}
	status := "Successful"
	if rejected > 0 && accepted > 0 {
		status = "Partially Successful"
	} else if rejected > 0 {
		status = "Failed"
	}
	if p.audit != nil {
		result := FileCompleted{
			FileName: fileName, ProcessingStart: processingStart, ProcessingEnd: time.Now().UTC(), Status: status,
			RecordsProcessed: len(records), RecordsAccepted: accepted, RecordsRejected: rejected, ParseFailures: parseFailures,
		}
		if err := p.audit.WriteFileCompleted(ctx, result); err != nil {
			return fmt.Errorf("write file completion audit event: %w", err)
		}
	}
	archiveDir := path.Join(p.config.RemoteDir, archiveStatus)
	if err := client.MkdirAll(archiveDir); err != nil {
		return p.processingFailure(ctx, fileName, "SYSTEM_EXCEPTION", "SYSTEM_EXCEPTION", fmt.Errorf("create %s directory: %w", archiveStatus, err))
	}
	if err := client.Rename(remotePath, path.Join(archiveDir, path.Base(remotePath))); err != nil {
		return p.processingFailure(ctx, fileName, "SYSTEM_EXCEPTION", "SYSTEM_EXCEPTION", fmt.Errorf("archive processed file: %w", err))
	}
	log.Printf("processed %d transactions from %s (%d accepted, %d rejected)", len(records), path.Base(remotePath), accepted, rejected)
	return nil
}

func (p *Poller) processingFailure(ctx context.Context, fileName, action, stage string, cause error) error {
	wrapped := cause
	auditErr := p.writeProcessingError(ctx, ProcessingError{FileName: fileName, Action: action, Stage: stage, Error: cause.Error()})
	if auditErr != nil {
		wrapped = fmt.Errorf("%w; write audit event: %v", cause, auditErr)
	}
	return wrapped
}
