package poller

import (
	"context"
	"errors"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const (
	knownAccount   = "a0000000-0000-0000-0000-000000000231"
	knownClient    = "00000000-0000-0000-0000-000000000203"
	unknownAccount = "00000000-0000-0000-0000-000000000299"
)

// One file: a known account twice (once in upper case), an unknown or
// deleted account, and an external reference that is not a UUID.
const linkJSON = `[
	{"transaction_id":"t1","account_id":"` + knownAccount + `","transfer_date":"2026-09-24T08:30:00Z","transaction_type":"CREDIT","amount":1,"currency":"SGD","status":"COMPLETED"},
	{"transaction_id":"t2","account_id":"A0000000-0000-0000-0000-000000000231","transfer_date":"2026-09-24T08:30:00Z","transaction_type":"DEBIT","amount":2,"currency":"SGD","status":"COMPLETED"},
	{"transaction_id":"t3","account_id":"` + unknownAccount + `","transfer_date":"2026-09-24T08:30:00Z","transaction_type":"CREDIT","amount":3,"currency":"SGD","status":"COMPLETED"},
	{"transaction_id":"t4","account_id":"ACC-000123","transfer_date":"2026-09-24T08:30:00Z","transaction_type":"CREDIT","amount":4,"currency":"SGD","status":"COMPLETED"}
]`

func pollOnce(t *testing.T, directory *fakeDirectory, content string) (*fakeRemote, *fakeStore, error) {
	t.Helper()
	remote := newFakeRemote()
	remote.add("link.json", content)
	store := &fakeStore{}
	p := New(Config{RemoteDir: "/reports"}, store, directory)
	p.connector = func(context.Context) (remoteSFTP, func(), error) { return remote, func() {}, nil }
	return remote, store, p.Poll(context.Background())
}

func TestPollLinksTransactionsToAccounts(t *testing.T) {
	directory := &fakeDirectory{accounts: map[string]string{knownAccount: knownClient}}

	remote, store, err := pollOnce(t, directory, linkJSON)

	require.NoError(t, err)
	// One lookup per file, each UUID once, never the external reference.
	require.Len(t, directory.asked, 1)
	assert.ElementsMatch(t, []string{knownAccount, unknownAccount}, directory.asked[0])

	require.Len(t, store.saved, 4)
	for _, saved := range store.saved[:2] {
		assert.Equal(t, "Matched", saved.ImportStatus)
		assert.Equal(t, knownAccount, saved.LinkedAccountID)
		assert.Equal(t, knownClient, saved.ClientID)
	}
	for _, saved := range store.saved[2:] {
		assert.Equal(t, "Unmatched", saved.ImportStatus)
		assert.Empty(t, saved.LinkedAccountID)
		assert.Empty(t, saved.ClientID)
	}
	assert.Len(t, remote.renamed, 1)
}

func TestPollWithoutAccountServiceSavesNothingAndKeepsTheFile(t *testing.T) {
	directory := &fakeDirectory{err: errors.New("account-service unavailable")}

	remote, store, err := pollOnce(t, directory, linkJSON)

	require.Error(t, err)
	assert.Contains(t, err.Error(), "look up accounts")
	assert.Empty(t, store.saved)
	assert.Empty(t, remote.renamed)
	assert.Contains(t, remote.files, "/reports/link.json")
}

func TestPollSkipsTheLookupWhenNoReferenceIsAUUID(t *testing.T) {
	directory := &fakeDirectory{err: errors.New("must not be called")}

	_, store, err := pollOnce(t, directory, validJSON)

	require.NoError(t, err)
	assert.Empty(t, directory.asked)
	require.Len(t, store.saved, 1)
	assert.Equal(t, "Unmatched", store.saved[0].ImportStatus)
}
