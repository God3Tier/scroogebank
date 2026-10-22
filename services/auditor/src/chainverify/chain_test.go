package main

// These tests run chain-verify against a real chain, in a database of their
// own so they do not race the repository tests, which share the test
// database. Each side logs in with only its role from data/schema.sql: the
// auditor appends, chain-verify checks, and the test itself plays someone
// with full database access tampering with the chain (ADR-0022 Validation).
// The anchors are kept in memory; the anchor package tests the S3 bucket.

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"testing"

	"auditor/src/internal/chain"
	"auditor/src/internal/models"
	"auditor/src/internal/repository/audit"
	"auditor/src/internal/repository/corrupted_log"
	auditSvc "auditor/src/internal/service/audit"
	corruptedLogSvc "auditor/src/internal/service/corrupted_logs"

	"github.com/lib/pq"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const chainDatabase = "chain_verify_test"

type found struct {
	sequence int64
	reason   models.FindingReason
}

// memoryAnchors stands in for the S3 Object Lock bucket.
type memoryAnchors struct {
	written []models.ChainAnchor
}

func (m *memoryAnchors) Latest(context.Context) (*models.ChainAnchor, error) {
	if len(m.written) == 0 {
		return nil, nil
	}
	latest := m.written[len(m.written)-1]
	return &latest, nil
}

func (m *memoryAnchors) Write(_ context.Context, anchor models.ChainAnchor) error {
	m.written = append(m.written, anchor)
	return nil
}

func TestChainVerifyFindsTampering(t *testing.T) {
	admin, auditor, verifier := openChainDatabase(t)
	ctx := context.Background()

	tests := map[string]struct {
		tamper func(t *testing.T)
		want   []found
	}{
		"finds nothing in an untouched chain": {
			tamper: func(*testing.T) {},
		},
		"finds a row edited without a new hash": {
			tamper: func(t *testing.T) {
				_, err := admin.Exec(`UPDATE audit_log SET action = 'DELETE' WHERE sequence_no = 2`)
				require.NoError(t, err)
			},
			want: []found{{2, models.HashMismatch}},
		},
		"finds a row edited and given a new hash, at the next row": {
			tamper: func(t *testing.T) {
				entries, err := audit.New(admin).GetChainPage(ctx, 1, 1)
				require.NoError(t, err)
				edited := entries[0]
				edited.Action = "DELETE"
				_, err = admin.Exec(`UPDATE audit_log SET action = $1, hash = $2 WHERE sequence_no = 2`,
					edited.Action, chain.Hash(edited))
				require.NoError(t, err)
			},
			want: []found{{3, models.ParentMismatch}},
		},
		"finds a deleted middle row": {
			tamper: func(t *testing.T) {
				_, err := admin.Exec(`DELETE FROM audit_log WHERE sequence_no = 2`)
				require.NoError(t, err)
			},
			want: []found{{3, models.ParentMismatch}, {3, models.SequenceGap}},
		},
		"finds a whole chain rewritten with every hash recomputed": {
			tamper: func(t *testing.T) {
				entries, err := audit.New(admin).GetChainPage(ctx, 0, 10)
				require.NoError(t, err)
				parent := ""
				for _, entry := range entries {
					entry.Action, entry.ParentHash = "DELETE", parent
					entry.Hash = chain.Hash(entry)
					_, err := admin.Exec(`UPDATE audit_log SET action = $1, parent_hash = $2, hash = $3 WHERE id = $4`,
						entry.Action, entry.ParentHash, entry.Hash, entry.ID)
					require.NoError(t, err)
					parent = entry.Hash
				}
			},
			// Only the anchor catches it: the rewritten chain is consistent.
			want: []found{{4, models.AnchorMismatch}},
		},
		"finds a chain cut back below the anchor": {
			tamper: func(t *testing.T) {
				_, err := admin.Exec(`DELETE FROM audit_log WHERE sequence_no >= 3`)
				require.NoError(t, err)
			},
			want: []found{{4, models.AnchorMismatch}},
		},
	}

	for name, test := range tests {
		t.Run(name, func(t *testing.T) {
			_, err := admin.Exec(`TRUNCATE audit_log, corrupted_audit_log`)
			require.NoError(t, err)
			writer := audit.New(auditor)
			for sequence := 1; sequence <= 4; sequence++ {
				require.NoError(t, writer.StoreAuditChain(ctx, models.AuditLogInput{
					EventID: fmt.Sprintf("evt-%d", sequence), EventType: "CRUD", Action: "UPDATE",
					SourceService: models.CRMServiceType, ActorID: "agent-1", Payload: `{"client_id":"c-1"}`,
				}))
			}
			anchors := &memoryAnchors{}
			require.Equal(t, exitOK, runVerify(ctx, verifier, anchors), "run before tampering")
			require.Len(t, anchors.written, 1)
			assert.Equal(t, int64(4), anchors.written[0].SequenceNo)
			test.tamper(t)

			wantRun := exitOK
			if len(test.want) > 0 {
				wantRun = exitNewFindings
			}
			assert.Equal(t, wantRun, runVerify(ctx, verifier, anchors), "run after tampering")
			assert.Equal(t, test.want, recordedFindings(t, admin))

			// The chain is still broken on the next run, but nothing new is
			// recorded or alarmed on.
			assert.Equal(t, exitOK, runVerify(ctx, verifier, anchors), "next run")
			assert.Equal(t, test.want, recordedFindings(t, admin))
		})
	}
}

func TestChainVerifyRoleCannotChangeTheChain(t *testing.T) {
	_, _, verifier := openChainDatabase(t)

	for _, statement := range []string{
		`INSERT INTO audit_log (id, sequence_no, event_type, action, source_service, actor_id, timestamp, payload, parent_hash, hash)
			VALUES ('00000000-0000-4000-8000-000000000001', 1, 'CRUD', 'UPDATE', 'CRM', 'agent-1', now(), '{}', '', 'hash')`,
		`UPDATE audit_log SET action = 'DELETE'`,
		`DELETE FROM audit_log`,
		`DELETE FROM corrupted_audit_log`,
	} {
		_, err := verifier.Exec(statement)
		var pqErr *pq.Error
		require.ErrorAs(t, err, &pqErr, statement)
		assert.Equal(t, pq.ErrorCode("42501"), pqErr.Code, "%s: want insufficient_privilege", statement)
	}
}

func runVerify(ctx context.Context, verifier *sql.DB, anchors *memoryAnchors) int {
	return verify(ctx, auditSvc.New(audit.New(verifier)), corruptedLogSvc.New(corrupted_log.New(verifier)), anchors)
}

// recordedFindings reads each finding's sequence number from the row it
// recorded, which may no longer be in the chain.
func recordedFindings(t *testing.T, admin *sql.DB) []found {
	t.Helper()
	rows, err := admin.Query(`
		SELECT (log_type->>'SequenceNo')::bigint AS sequence_no, reason
		FROM corrupted_audit_log
		ORDER BY sequence_no, reason`)
	require.NoError(t, err)
	defer rows.Close()

	var findings []found
	for rows.Next() {
		var finding found
		require.NoError(t, rows.Scan(&finding.sequence, &finding.reason))
		findings = append(findings, finding)
	}
	require.NoError(t, rows.Err())
	return findings
}

// openChainDatabase recreates chainDatabase from data/schema.sql and returns
// connections as the test's admin user, as a login holding auditor_service
// and as a login holding auditor_chain_verify. The test password is not a
// secret: the database is a disposable test container.
func openChainDatabase(t *testing.T) (admin, auditor, verifier *sql.DB) {
	t.Helper()
	user := envOrDefault("DB_USER", "postgres")
	password := envOrDefault("DB_PASSWORD", "postgres")

	server := open(t, user, password, envOrDefault("DB_NAME", "myapp"))
	for _, statement := range []string{
		`DROP DATABASE IF EXISTS ` + chainDatabase + ` WITH (FORCE)`,
		`CREATE DATABASE ` + chainDatabase,
	} {
		_, err := server.Exec(statement)
		require.NoError(t, err, statement)
	}

	admin = open(t, user, password, chainDatabase)
	_, currentFile, _, ok := runtime.Caller(0)
	require.True(t, ok, "locate the chain-verify test directory")
	schema, err := os.ReadFile(filepath.Join(filepath.Dir(currentFile), "..", "..", "data", "schema.sql"))
	require.NoError(t, err)
	_, err = admin.Exec(string(schema))
	require.NoError(t, err, "load data/schema.sql")

	for login, role := range map[string]string{
		"chain_verify_writer_test": "auditor_service",
		"chain_verify_reader_test": "auditor_chain_verify",
	} {
		_, err := admin.Exec(`DO $$ BEGIN
			IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '` + login + `') THEN
				CREATE ROLE ` + login + ` LOGIN PASSWORD 'test' IN ROLE ` + role + `;
			END IF;
		END $$`)
		require.NoError(t, err, "create login %s", login)
	}

	return admin, open(t, "chain_verify_writer_test", "test", chainDatabase),
		open(t, "chain_verify_reader_test", "test", chainDatabase)
}

func open(t *testing.T, user, password, database string) *sql.DB {
	t.Helper()
	dsn := "host=" + envOrDefault("DB_HOST", "localhost") + " port=" + envOrDefault("DB_PORT", "5433") +
		" user=" + user + " password=" + password + " dbname=" + database + " sslmode=disable"
	db, err := sql.Open("postgres", dsn)
	require.NoError(t, err)
	require.NoError(t, db.Ping(), "connect to %s as %s", database, user)
	t.Cleanup(func() { db.Close() })
	return db
}

func envOrDefault(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}
