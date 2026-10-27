package poller

import (
	"context"
	"errors"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"receiver/src/internal/models"
)

const (
	knownAccount   = "a0000000-0000-0000-0000-000000000231"
	knownClient    = "00000000-0000-0000-0000-000000000203"
	unknownAccount = "00000000-0000-0000-0000-000000000299"
)

var sgdAccount = models.Account{ClientID: knownClient, Currency: "SGD"}

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
	directory := &fakeDirectory{accounts: map[string]models.Account{knownAccount: sgdAccount}}

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

func TestPollAuditsOnlyTheFirstLookupFailureOfAnOutage(t *testing.T) {
	ctx := context.Background()
	remote := newFakeRemote()
	remote.add("link.json", linkJSON)
	directory := &fakeDirectory{err: errors.New("account-service unavailable")}
	audit := &fakeEvents{}
	p := New(Config{RemoteDir: "/reports"}, &fakeStore{}, directory, Audit{Events: audit, Relay: &fakeRelay{}})
	p.connector = func(context.Context) (remoteSFTP, func(), error) { return remote, func() {}, nil }

	// The file is retried on every poll, but the outage is audited once.
	for range 3 {
		require.Error(t, p.Poll(ctx))
	}
	require.Len(t, audit.failures, 1)
	assert.Equal(t, "link.json", audit.failures[0].FileName)
	assert.Equal(t, "SYSTEM_EXCEPTION", audit.failures[0].Action)

	// account-service is back: the file is imported.
	directory.err = nil
	directory.accounts = map[string]models.Account{knownAccount: sgdAccount}
	require.NoError(t, p.Poll(ctx))
	require.Len(t, audit.completed, 1)

	// A later outage is audited again, once per waiting file.
	directory.err = errors.New("account-service unavailable again")
	remote.add("next.json", linkJSON)
	for range 2 {
		require.Error(t, p.Poll(ctx))
	}
	require.Len(t, audit.failures, 2)
	assert.Equal(t, "next.json", audit.failures[1].FileName)
}

func TestPollSkipsTheLookupWhenNoReferenceIsAUUID(t *testing.T) {
	directory := &fakeDirectory{err: errors.New("must not be called")}

	_, store, err := pollOnce(t, directory, validJSON)

	require.NoError(t, err)
	assert.Empty(t, directory.asked)
	require.Len(t, store.saved, 1)
	assert.Equal(t, "Unmatched", store.saved[0].ImportStatus)
}

// A transaction on a CRM account must be in the account's currency. One
// without a currency, or in another one, is rejected like any invalid row;
// a code in lower case still matches. An Unmatched row has no account to
// compare with, so it is kept either way.
const currencyJSON = `[
	{"transaction_id":"t1","account_id":"` + knownAccount + `","transfer_date":"2026-09-24T08:30:00Z","transaction_type":"CREDIT","amount":1,"currency":"sgd","status":"COMPLETED"},
	{"transaction_id":"t2","account_id":"` + knownAccount + `","transfer_date":"2026-09-24T08:30:00Z","transaction_type":"CREDIT","amount":2,"currency":"USD","status":"COMPLETED"},
	{"transaction_id":"t3","account_id":"` + knownAccount + `","transfer_date":"2026-09-24T08:30:00Z","transaction_type":"CREDIT","amount":3,"status":"COMPLETED"},
	{"transaction_id":"t4","account_id":"` + unknownAccount + `","transfer_date":"2026-09-24T08:30:00Z","transaction_type":"CREDIT","amount":4,"status":"COMPLETED"}
]`

func TestPollRejectsTransactionsNotInTheirAccountsCurrency(t *testing.T) {
	remote := newFakeRemote()
	remote.add("currency.json", currencyJSON)
	store := &fakeStore{}
	audit := &fakeEvents{}
	directory := &fakeDirectory{accounts: map[string]models.Account{knownAccount: sgdAccount}}
	p := New(Config{RemoteDir: "/reports"}, store, directory, Audit{Events: audit, Relay: &fakeRelay{}})
	p.connector = func(context.Context) (remoteSFTP, func(), error) { return remote, func() {}, nil }

	require.NoError(t, p.Poll(context.Background()))

	require.Len(t, store.saved, 2)
	assert.Equal(t, "t1", store.saved[0].TransactionID)
	assert.Equal(t, "SGD", store.saved[0].Currency)
	assert.Equal(t, "Matched", store.saved[0].ImportStatus)
	assert.Equal(t, "t4", store.saved[1].TransactionID)
	assert.Equal(t, "Unmatched", store.saved[1].ImportStatus)

	require.Len(t, audit.completed, 1)
	completed := audit.completed[0]
	assert.Equal(t, "Partially Successful", completed.Status)
	assert.Equal(t, 2, completed.RecordsAccepted)
	assert.Equal(t, 2, completed.RecordsRejected)
	require.Len(t, completed.ParseFailures, 2)
	assert.Equal(t, "record 2", completed.ParseFailures[0].RecordRef)
	assert.Contains(t, completed.ParseFailures[0].Reason, "currency USD does not match")
	assert.Equal(t, "record 3", completed.ParseFailures[1].RecordRef)
	assert.Contains(t, completed.ParseFailures[1].Reason, "currency is required")
	assert.Equal(t, [][2]string{{"/reports/currency.json", "/reports/rejected/currency.json"}}, remote.renamed)
}
