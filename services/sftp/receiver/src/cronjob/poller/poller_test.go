package poller

import (
	"context"
	"errors"
	"testing"
	"time"

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
		"malformed JSON is rejected": {
			setup:           func(r *fakeRemote, _ *fakeStore, _ *Poller) { r.add("broken.json", `[{`) },
			wantDestination: "/reports/rejected/broken.json",
			wantMoved:       1,
		},
		"JSON null is not a transaction array": {
			setup:           func(r *fakeRemote, _ *fakeStore, _ *Poller) { r.add("broken.json", `null`) },
			wantDestination: "/reports/rejected/broken.json",
			wantMoved:       1,
		},
		"another extension is rejected": {
			setup:           func(r *fakeRemote, _ *fakeStore, _ *Poller) { r.add("report.csv", "transaction_id\nt1\n") },
			wantDestination: "/reports/rejected/report.csv",
			wantMoved:       1,
		},
		"uploads in progress and directories are left alone": {
			setup: func(r *fakeRemote, _ *fakeStore, _ *Poller) {
				r.add("report.json.tmp", `[`)
				r.add("REPORT.JSON.TMP", `[`)
				r.dirs = []string{"processed", "rejected"}
			},
		},
		"an upper-case extension is imported": {
			setup:     func(r *fakeRemote, _ *fakeStore, _ *Poller) { r.add("VALID.JSON", validJSON) },
			wantSaved: 1, wantMoved: 1, wantDestination: "/reports/processed/VALID.JSON",
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
				r.add("a-unreadable.json", validJSON)
				r.openErrs = map[string]error{"a-unreadable.json": errors.New("file unavailable")}
				r.add("b-valid.json", validJSON)
			},
			wantErr: "a-unreadable.json", wantSaved: 1, wantMoved: 1,
		},
		"a rejected file does not stop later files": {
			setup: func(r *fakeRemote, _ *fakeStore, _ *Poller) {
				r.add("a-broken.json", `{`)
				r.add("b-valid.json", validJSON)
			},
			wantSaved: 1, wantMoved: 2,
		},
	}

	for name, tt := range tests {
		t.Run(name, func(t *testing.T) {
			remote := newFakeRemote()
			store := &fakeStore{}
			p := New(Config{RemoteDir: "/reports"}, store, &fakeDirectory{})
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

func TestPollStopsStartingFilesNearItsDeadline(t *testing.T) {
	tests := map[string]struct {
		left      time.Duration
		wantSaved int
	}{
		"too little time left: no file is started": {left: 30 * time.Second, wantSaved: 0},
		"enough time left: every file is imported": {left: 2 * time.Minute, wantSaved: 2},
	}

	for name, tt := range tests {
		t.Run(name, func(t *testing.T) {
			remote := newFakeRemote()
			remote.add("a.json", validJSON)
			remote.add("b.json", validJSON)
			store := &fakeStore{}
			relay := &fakeRelay{}
			ctx, cancel := context.WithTimeout(context.Background(), tt.left)
			defer cancel()

			require.NoError(t, auditedPoller(remote, store, relay).Poll(ctx))

			assert.Len(t, store.saved, tt.wantSaved)
			assert.Len(t, remote.renamed, tt.wantSaved, "files not started stay for the next poll")
			assert.Equal(t, 1, relay.runs, "the poll's audit events are still relayed")
		})
	}
}
