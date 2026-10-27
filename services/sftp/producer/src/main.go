package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"producer/src/accounts"
	"producer/src/fault"
	"producer/src/transaction"
	"strconv"
	"strings"
	"syscall"
	"time"
)

func main() {
	faultKind := flag.String("fault", "", "publish one fault file of this kind and exit: "+strings.Join(fault.Kinds, ", "))
	history := flag.Bool("history", false, "publish deterministic six-month synthetic history and exit")
	flag.Parse()
	destDir := getenv("OUTPUT_DIR", "/output")
	source := accounts.FromEnv()
	if *faultKind != "" {
		if err := publishFault(*faultKind, destDir, source); err != nil {
			log.Fatalf("publish %s fault: %v", *faultKind, err)
		}
		return
	}
	if *history {
		if err := publishHistory(destDir, source); err != nil {
			log.Fatalf("publish synthetic history: %v", err)
		}
		return
	}
	interval := getenvDuration("INTERVAL_SECONDS", 300)

	log.Printf("starting: dest=%s interval=%s\n", destDir, interval)

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	// Publish once immediately on startup, then on every tick.
	run(destDir, source)

	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			log.Println("shutting down")
			return
		case <-ticker.C:
			run(destDir, source)
		}
	}
}

func publishHistory(destDir string, source accounts.Source) error {
	accounts, err := source.Accounts()
	if err != nil {
		return err
	}
	if !source.Synced() || len(accounts) < 4 {
		return fmt.Errorf("six-month history requires at least four synced CRM accounts for signal cases and healthy controls")
	}
	rows := transaction.GenerateHistory(accounts, time.Now().UTC())
	if len(rows) == 0 {
		return fmt.Errorf("six-month history generated no transactions")
	}
	content, err := json.Marshal(rows)
	if err != nil {
		return fmt.Errorf("encode synthetic history: %w", err)
	}
	name, err := stageFile(content, ".json", time.Now().UTC(), destDir)
	if err != nil {
		return err
	}
	log.Printf("published %d deterministic synthetic transactions in %s", len(rows), name)
	return nil
}

// run publishes one file. With a synced account list, its transactions use
// only the accounts the CRM has, each in its account's currency, and with
// none it publishes nothing (ADR-0045).
func run(destDir string, source accounts.Source) {
	list, err := source.Accounts()
	if err != nil {
		log.Printf("nothing published: %v\n", err)
		return
	}
	if source.Synced() && len(list) == 0 {
		log.Printf("nothing published: %s lists no CRM accounts\n", source.File)
		return
	}
	transactions := transaction.GenerateRandomSet(list...)
	if err := stageContent(transactions, destDir); err != nil {
		log.Printf("stage failed: %v\n", err)
		return
	}
	log.Printf("published a new file into %s\n", destDir)
}

func stageContent(transactions []transaction.Transaction, destDir string) error {
	var content []byte

	content = append(content, '[')
	for indx, transaction := range transactions {
		val, err := json.Marshal(transaction)
		if err != nil {
			log.Printf("Invalid transaction %s\n", err.Error())
		}
		content = append(content, val...)
		if indx != len(transactions)-1 {
			content = append(content, ',')
		}
	}
	content = append(content, ']')

	log.Printf("Writing: %s\n", string(content))
	_, err := stageFile(content, ".json", time.Now().UTC(), destDir)
	return err
}

// publishFault publishes the files of one fault for the demo (ADR-0024
// decision 1). Files a second apart get names in the order they must be
// read. A fault is asked for on purpose, so it is published even when the
// synced list is empty, with random external account references.
func publishFault(kind, destDir string, source accounts.Source) error {
	list, err := source.Accounts()
	if err != nil {
		return err
	}
	files, err := fault.Build(kind, list)
	if err != nil {
		return err
	}
	now := time.Now().UTC()
	for i, file := range files {
		name, err := stageFile(file.Content, file.Extension, now.Add(time.Duration(i)*time.Second), destDir)
		if err != nil {
			return err
		}
		log.Printf("published %s fault file %s\n", kind, name)
	}
	return nil
}

// stageFile writes content as report-<time>-<id><extension>: first under a
// .tmp name, then renamed, so the reader never sees a half-written file
// (ADR-0024 decision 2). It returns the file's name.
func stageFile(content []byte, extension string, at time.Time, destDir string) (string, error) {
	buf := make([]byte, 4)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	uniq := hex.EncodeToString(buf)
	ts := at.Format("20060102T150405Z")
	name := fmt.Sprintf("report-%s-%s%s", ts, uniq, extension)
	final := filepath.Join(destDir, name)
	tmp := final + ".tmp"

	out, err := os.Create(tmp)
	if err != nil {
		return "", err
	}

	if _, err := out.Write(content); err != nil {
		out.Close()
		os.Remove(tmp)
		return "", err
	}

	if err := out.Close(); err != nil {
		os.Remove(tmp)
		return "", err
	}

	return name, os.Rename(tmp, final)
}

func getenv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func getenvDuration(key string, fallbackSeconds int) time.Duration {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return time.Duration(n) * time.Second
		}
	}
	return time.Duration(fallbackSeconds) * time.Second
}
