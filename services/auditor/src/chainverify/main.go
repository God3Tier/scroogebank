// chain-verify checks the whole audit chain once and exits (ADR-0022
// decision 3). EventBridge Scheduler starts it daily as its own ECS task, from
// the auditor's image. It reads audit-db directly, never writes to audit_log,
// and records each problem it finds in corrupted_audit_log.
//
// It exits with status 0 when it recorded no new finding, 1 when the check
// could not finish, and 2 when it recorded a new finding.
//
// Like the server in src/serverd, it reads the database settings from DB_NAME,
// HOST, USER, PASSWORD and SSLMODE (src/internal/bootstrap). In AWS it logs in
// as its own user, which holds only the auditor_chain_verify role.
package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"

	"auditor/src/internal/bootstrap"
	"auditor/src/internal/repository"
	auditSvc "auditor/src/internal/service/audit"
	corruptedLogSvc "auditor/src/internal/service/corrupted_logs"
)

const (
	exitOK          = 0
	exitFailed      = 1
	exitNewFindings = 2
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	code := run(ctx)
	stop()
	os.Exit(code)
}

func run(ctx context.Context) int {
	db, err := bootstrap.InitialiseDB()
	if err != nil {
		log.Printf("chain-verify: open audit-db: %v", err)
		return exitFailed
	}
	defer db.Close()

	registry := repository.New(db)
	return verify(ctx, auditSvc.New(registry.GetAudit()), corruptedLogSvc.New(registry.GetCorruptedLog()))
}
