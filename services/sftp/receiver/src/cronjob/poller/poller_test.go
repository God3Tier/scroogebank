package poller

import (
	"context"
	"errors"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestPoll(t *testing.T) {
	dbErr := errors.New("database unavailable")
	tests := map[string]struct {
		setup           func(*fakeRemote, *fakeStore, *Poller)
		wantErr         string
		wantSaved       int
		wantMoved       int
		wantDestination string
	}{
		"connection failure": {
			setup: func(_ *fakeRemote, _ *fakeStore, p *Poller) {
				p.connector = func(context.Context) (remoteSFTP, func(), error) {
					return nil, nil, errors.New("connection refused")
				}
			},
			wantErr: "connection refused",
		},
		"directory listing failure": {
			setup:   func(r *fakeRemote, _ *fakeStore, _ *Poller) { r.readDirErr = errors.New("permission denied") },
			wantErr: "permission denied",
		},
		"remote open failure": {
			setup: func(r *fakeRemote, _ *fakeStore, _ *Poller) {
				r.add("broken.json", `[]`)
				r.openErr = errors.New("file unavailable")
			},
			wantErr: "file unavailable",
		},
		"remote read failure": {
			setup: func(r *fakeRemote, _ *fakeStore, _ *Poller) {
				r.add("broken.json", `[]`)
				r.readErr = errors.New("network interrupted")
			},
			wantErr: "network interrupted",
		},
		"remote close failure": {
			setup: func(r *fakeRemote, _ *fakeStore, _ *Poller) {
				r.add("broken.json", `[]`)
				r.closeErr = errors.New("close failed")
			},
			wantErr: "close failed",
		},
		"malformed JSON": {
			setup:   func(r *fakeRemote, _ *fakeStore, _ *Poller) { r.add("broken.json", `[{`) },
			wantErr: "parse JSON",
		},
		"JSON null is not a transaction array": {
			setup:   func(r *fakeRemote, _ *fakeStore, _ *Poller) { r.add("broken.json", `null`) },
			wantErr: "expected an array",
		},
		"database error leaves file available for retry": {
			setup: func(r *fakeRemote, s *fakeStore, _ *Poller) {
				r.add("retry.json", validJSON)
				s.err = dbErr
			},
			wantErr: "database unavailable",
		},
		"bad transaction is quarantined": {
			setup:           func(r *fakeRemote, _ *fakeStore, _ *Poller) { r.add("invalid.json", `[{}]`) },
			wantDestination: "/reports/rejected/invalid.json",
			wantMoved:       1,
		},
		"archive directory creation failure": {
			setup: func(r *fakeRemote, _ *fakeStore, _ *Poller) {
				r.add("valid.json", `[ {"transaction_id":"t1","account_id":"a1","transfer_date":"2026-09-24T08:30:00Z","transaction_type":"CREDIT","amount":1,"currency":"SGD","status":"COMPLETED"} ]`)
				r.mkdirErr = errors.New("mkdir failed")
			},
			wantErr: "mkdir failed", wantSaved: 1,
		},
		"archive rename failure": {
			setup: func(r *fakeRemote, _ *fakeStore, _ *Poller) {
				r.add("valid.json", `[ {"transaction_id":"t1","account_id":"a1","transfer_date":"2026-09-24T08:30:00Z","transaction_type":"CREDIT","amount":1,"currency":"SGD","status":"COMPLETED"} ]`)
				r.renameErr = errors.New("rename failed")
			},
			wantErr: "rename failed", wantSaved: 1,
		},
		"valid file saves and archives": {
			setup: func(r *fakeRemote, _ *fakeStore, _ *Poller) {
				r.add("valid.json", validJSON)
			},
			wantSaved: 1, wantMoved: 1, wantDestination: "/reports/processed/valid.json",
		},
		"continues processing later files after a failure": {
			setup: func(r *fakeRemote, _ *fakeStore, _ *Poller) {
				r.add("a-broken.json", `{`)
				r.add("b-valid.json", validJSON)
			},
			wantErr: "a-broken.json", wantSaved: 1, wantMoved: 1,
		},
	}

	for name, tt := range tests {
		t.Run(name, func(t *testing.T) {
			remote := newFakeRemote()
			store := &fakeStore{}
			p := New(Config{RemoteDir: "/reports"}, store)
			p.connector = func(context.Context) (remoteSFTP, func(), error) { return remote, func() {}, nil }
			tt.setup(remote, store, p)
			err := p.Poll(context.Background())
			if tt.wantErr == "" {
				require.NoError(t, err)
			} else {
				require.Error(t, err)
				assert.Contains(t, err.Error(), tt.wantErr)
			}
			assert.Len(t, store.saved, tt.wantSaved)
			assert.Len(t, remote.renamed, tt.wantMoved)
			if tt.wantDestination != "" {
				require.Len(t, remote.renamed, 1)
				assert.Equal(t, tt.wantDestination, remote.renamed[0][1])
			}
			if name == "database error leaves file available for retry" {
				assert.ErrorIs(t, err, dbErr)
				assert.Contains(t, remote.files, "/reports/retry.json")
			}
		})
	}
}
