package poller

import (
	"context"
	"time"
)

// failureKind is one way a poll can fail. Each kind has its own audit action
// and stage.
type failureKind string

const (
	connectionFailure failureKind = "connection" // connecting, or listing the remote directory
	readFailure       failureKind = "read"       // opening, reading or closing a file
	formatFailure     failureKind = "format"     // a file that is not a JSON array of transactions, or not a .json file
	lookupFailure     failureKind = "lookup"     // account-service could not answer
	saveFailure       failureKind = "save"       // the import did not commit
	archiveFailure    failureKind = "archive"    // the file could not be moved out of the remote directory
)

func (k failureKind) audit() (action, stage string) {
	switch k {
	case connectionFailure:
		return "CONNECTION_FAILURE", "CONNECTION"
	case formatFailure:
		return "FILE_FORMAT_INVALID", "FILE_FORMAT"
	default:
		return "SYSTEM_EXCEPTION", "SYSTEM_EXCEPTION"
	}
}

// failureKey is one kind of failure on one file. A connection failure has no
// file.
type failureKey struct {
	file string
	kind failureKind
}

// failureEpisode is a failure that has happened on every poll since it began.
type failureEpisode struct {
	since   time.Time
	audited bool
}

// recordFailure audits a failure once per episode (ADR-0024 decision 5): the
// first time it happens, with that time as occurred_at. Later polls that hit
// the same failure are not audited until its episode ends, so a new episode
// has its own occurred_at, and so its own event ID. If the event cannot be
// written, the episode stays unaudited and the next poll tries again with the
// same occurred_at, and so the same event ID.
func (p *Poller) recordFailure(ctx context.Context, key failureKey, cause error) error {
	episode, ok := p.failures[key]
	if !ok {
		episode = &failureEpisode{since: p.now().UTC()}
		p.failures[key] = episode
	}
	if episode.audited {
		return nil
	}
	action, stage := key.kind.audit()
	if err := p.writeProcessingError(ctx, ProcessingError{
		FileName: key.file, Action: action, Stage: stage, Error: cause.Error(), OccurredAt: episode.since,
	}); err != nil {
		return err
	}
	episode.audited = true
	return nil
}

// endFailure ends one failure's episode: the step it failed at has succeeded.
func (p *Poller) endFailure(key failureKey) {
	delete(p.failures, key)
}

// endFileFailures ends every episode of a file that has been imported.
func (p *Poller) endFileFailures(file string) {
	for key := range p.failures {
		if key.file == file {
			delete(p.failures, key)
		}
	}
}

// endListedFailures ends the connection failure's episode, now that the
// server has listed its directory, and the episodes of files no longer
// waiting in it.
func (p *Poller) endListedFailures(waiting []string) {
	listed := make(map[string]bool, len(waiting))
	for _, name := range waiting {
		listed[name] = true
	}
	for key := range p.failures {
		if key.kind == connectionFailure || !listed[key.file] {
			delete(p.failures, key)
		}
	}
}
