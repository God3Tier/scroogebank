package poller

import (
	"context"
	"errors"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestPollRejectsAFileItCannotRead(t *testing.T) {
	tests := map[string]struct {
		file, content string
		wantError     string
	}{
		"truncated JSON":          {file: "report.json", content: `[{"transaction_id":"t1"`, wantError: "parse JSON transaction file"},
		"empty file":              {file: "report.json", content: ``, wantError: "parse JSON transaction file"},
		"an object, not an array": {file: "report.json", content: `{}`, wantError: "parse JSON transaction file"},
		"JSON null":               {file: "report.json", content: `null`, wantError: "expected an array"},
		"another extension":       {file: "report.csv", content: validJSON, wantError: "unsupported file type"},
		"no extension":            {file: "report", content: validJSON, wantError: "unsupported file type"},
	}

	for name, tt := range tests {
		t.Run(name, func(t *testing.T) {
			events := &fakeEvents{}
			remote := newFakeRemote()
			store := &fakeStore{}
			remote.add(tt.file, tt.content)

			require.NoError(t, episodePoller(remote, store, &fakeDirectory{}, &fakeRelay{}, events).Poll(context.Background()),
				"a rejected file is handled, not a failed poll")

			assert.Equal(t, [][2]string{{"/reports/" + tt.file, "/reports/rejected/" + tt.file}}, remote.renamed)
			assert.Empty(t, store.saved)
			assert.Empty(t, events.completed)
			require.Len(t, events.failures, 1)
			failure := events.failures[0]
			assert.Equal(t, "FILE_FORMAT_INVALID", failure.Action)
			assert.Equal(t, "FILE_FORMAT", failure.Stage)
			assert.Equal(t, tt.file, failure.FileName)
			assert.Contains(t, failure.Error, tt.wantError)
			assert.Equal(t, []string{"FILE_FORMAT_INVALID:" + tt.file}, eventIDs(store.soloEvents))
		})
	}
}

func TestPollKeepsARejectedFileUntilItsEventIsWritten(t *testing.T) {
	ctx := context.Background()
	events := &fakeEvents{}
	remote := newFakeRemote()
	store := &fakeStore{auditErr: errors.New("database unavailable")}
	relay := &fakeRelay{sendErr: errors.New("SQS unavailable")}
	p := episodePoller(remote, store, &fakeDirectory{}, relay, events)
	remote.add("broken.json", `[{`)

	err := p.Poll(ctx)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "SQS unavailable", "the unwritten event is reported")
	assert.Empty(t, remote.renamed, "moved now, the file would leave its event behind")
	assert.Contains(t, remote.files, "/reports/broken.json")

	store.auditErr = nil
	require.NoError(t, p.Poll(ctx))

	assert.Equal(t, [][2]string{{"/reports/broken.json", "/reports/rejected/broken.json"}}, remote.renamed)
	require.Len(t, events.failures, 2, "the event is built again on the next poll")
	assert.Equal(t, events.failures[0].OccurredAt, events.failures[1].OccurredAt,
		"the retry keeps the episode's time, and so its event ID")
	assert.Len(t, store.soloEvents, 1)
}

func TestPollAuditsARejectedFileThatCannotBeMovedOnce(t *testing.T) {
	ctx := context.Background()
	events := &fakeEvents{}
	remote := newFakeRemote()
	p := episodePoller(remote, &fakeStore{}, &fakeDirectory{}, &fakeRelay{}, events)
	remote.add("report.csv", "transaction_id\nt1\n")
	remote.renameErr = errors.New("permission denied")

	for range 2 {
		err := p.Poll(ctx)
		require.Error(t, err)
		assert.Contains(t, err.Error(), "move file to rejected")
	}
	require.Len(t, events.failures, 2, "each failure is audited once, not on every poll")
	assert.Equal(t, "FILE_FORMAT_INVALID", events.failures[0].Action)
	assert.Equal(t, "SYSTEM_EXCEPTION", events.failures[1].Action)
	assert.Contains(t, remote.files, "/reports/report.csv")

	remote.renameErr = nil
	require.NoError(t, p.Poll(ctx))

	assert.Equal(t, [][2]string{{"/reports/report.csv", "/reports/rejected/report.csv"}}, remote.renamed)
	assert.Len(t, events.failures, 2)
}
