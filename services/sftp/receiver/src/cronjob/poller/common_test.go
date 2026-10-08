package poller

import (
	"context"
	"io"
	"os"
	"path"
	"strings"
	"testing"
	"time"

	"receiver/src/internal/models"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestNormalizeTransaction(t *testing.T) {
	tests := []struct {
		name     string
		input    models.Transaction
		wantType string
		wantStat string
		wantErr  bool
	}{
		{
			name:     "maps producer debit and completed values",
			input:    validTransaction("DEBIT", "COMPLETED"),
			wantType: "Withdrawal",
			wantStat: "Completed",
		},
		{
			name:     "maps producer credit and pending values",
			input:    validTransaction("CREDIT", "PENDING"),
			wantType: "Deposit",
			wantStat: "Pending",
		},
		{
			name:    "rejects unknown transaction type",
			input:   validTransaction("TRANSFER", "COMPLETED"),
			wantErr: true,
		},
		{
			name:    "rejects unknown transaction status",
			input:   validTransaction("CREDIT", "UNKNOWN"),
			wantErr: true,
		},
		{
			name:    "rejects negative amount",
			input:   models.Transaction{TransactionID: "test-5", AccountID: "account-5", TransactionType: "CREDIT", Amount: -1, Currency: "SGD", TransferDate: testDate(), Status: "COMPLETED"},
			wantErr: true,
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			transaction := test.input
			err := normalizeTransaction(&transaction)
			if test.wantErr {
				require.Error(t, err)
				return
			}
			require.NoError(t, err)
			assert.Equal(t, test.wantType, transaction.TransactionType)
			assert.Equal(t, test.wantStat, transaction.Status)
		})
	}
}

const validJSON = `[{"transaction_id":"t1","account_id":"a1","transfer_date":"2026-09-24T08:30:00Z","transaction_type":"CREDIT","amount":1,"currency":"SGD","status":"COMPLETED"}]`

type fakeStore struct {
	saved []models.Transaction
	err   error
}

func (s *fakeStore) SaveTransaction(_ context.Context, tx models.Transaction) error {
	if s.err != nil {
		return s.err
	}
	s.saved = append(s.saved, tx)
	return nil
}

// fakeDirectory stands in for account-service: accounts maps account ID to
// client ID.
type fakeDirectory struct {
	accounts map[string]string
	err      error
	asked    [][]string
}

func (d *fakeDirectory) Lookup(_ context.Context, ids []string) (map[string]string, error) {
	d.asked = append(d.asked, ids)
	if d.err != nil {
		return nil, d.err
	}
	found := make(map[string]string)
	for _, id := range ids {
		if clientID, ok := d.accounts[id]; ok {
			found[id] = clientID
		}
	}
	return found, nil
}

type fakeRemote struct {
	files                                                       map[string]string
	readDirErr, openErr, readErr, closeErr, mkdirErr, renameErr error
	renamed                                                     [][2]string
}

func newFakeRemote() *fakeRemote               { return &fakeRemote{files: make(map[string]string)} }
func (r *fakeRemote) add(name, content string) { r.files["/reports/"+name] = content }
func (r *fakeRemote) ReadDir(string) ([]os.FileInfo, error) {
	if r.readDirErr != nil {
		return nil, r.readDirErr
	}
	entries := make([]os.FileInfo, 0, len(r.files))
	for filename := range r.files {
		entries = append(entries, fakeFileInfo{name: path.Base(filename)})
	}
	return entries, nil
}
func (r *fakeRemote) Open(filename string) (remoteFile, error) {
	if r.openErr != nil {
		return nil, r.openErr
	}
	content, ok := r.files[filename]
	if !ok {
		return nil, os.ErrNotExist
	}
	return &fakeRemoteFile{Reader: strings.NewReader(content), readErr: r.readErr, closeErr: r.closeErr}, nil
}
func (r *fakeRemote) MkdirAll(string) error { return r.mkdirErr }
func (r *fakeRemote) Rename(old, new string) error {
	if r.renameErr != nil {
		return r.renameErr
	}
	r.renamed = append(r.renamed, [2]string{old, new})
	delete(r.files, old)
	return nil
}

type fakeRemoteFile struct {
	io.Reader
	readErr, closeErr error
	failed            bool
}

func (f *fakeRemoteFile) Read(p []byte) (int, error) {
	if f.readErr != nil && !f.failed {
		f.failed = true
		return 0, f.readErr
	}
	return f.Reader.Read(p)
}
func (f *fakeRemoteFile) Close() error { return f.closeErr }

type fakeFileInfo struct{ name string }

func (f fakeFileInfo) Name() string     { return f.name }
func (fakeFileInfo) Size() int64        { return 1 }
func (fakeFileInfo) Mode() os.FileMode  { return 0 }
func (fakeFileInfo) ModTime() time.Time { return time.Time{} }
func (fakeFileInfo) IsDir() bool        { return false }
func (fakeFileInfo) Sys() any           { return nil }

func validTransaction(transactionType, status string) models.Transaction {
	return models.Transaction{
		TransactionID:   "test-transaction",
		AccountID:       "test-account",
		TransactionType: transactionType,
		Amount:          1,
		Currency:        "SGD",
		TransferDate:    testDate(),
		Status:          status,
	}
}

func testDate() time.Time {
	return time.Date(2026, 9, 24, 8, 30, 0, 0, time.UTC)
}
