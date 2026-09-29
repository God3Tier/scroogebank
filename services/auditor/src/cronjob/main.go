package cronjob

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/robfig/cron/v3"

	"auditor/src/internal/bootstrap"
	"auditor/src/internal/repository"
	auditSvc "auditor/src/internal/service/audit"
)

type Checked struct {
	sequence int
	offset   int
}

func main() {
	if err := run(); err != nil {
		panic("Unable to run cronjob")
	}
}

func run() error {
	checked := Checked{
		sequence: 0,
		offset:   10000,
	}

	ctx, cancel := signal.NotifyContext(
		context.Background(),
		os.Interrupt,
		syscall.SIGTERM,
	)
	defer cancel()

	// Dependencies
	db, err := bootstrap.InitialiseDB()
	if err != nil {
		return err
	}

	repo := repository.New(db)
	auditSvc := auditSvc.New(repo.GetAudit())

	// Jobs
	jobs := New(auditSvc, repo.GetAudit())

	// Scheduler
	c := cron.New()

	if _, err := c.AddFunc("@every 24h", func() {
		jobs.VerifyHistoricalChain(ctx)
	}); err != nil {
		log.Fatal(err)
	}

	if _, err := c.AddFunc("@every 1h", func() {
		jobs.ReadOffset(ctx, &checked)
	}); err != nil {
		log.Fatal(err)
	}

	c.Start()

	log.Println("Auditor cron service started")

	<-ctx.Done()

	log.Println("Stopping auditor cron service...")

	stopCtx := c.Stop()

	select {
	case <-stopCtx.Done():
		log.Println("Cron jobs stopped")
	case <-time.After(30 * time.Second):
		log.Println("Forced shutdown after 30 seconds")
	}

	return nil
}
