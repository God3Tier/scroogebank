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
	"producer/src/fault"
	"producer/src/transaction"
	"strconv"
	"strings"
	"syscall"
	"time"
)

func main() {
	faultKind := flag.String("fault", "", "publish one fault file of this kind and exit: "+strings.Join(fault.Kinds, ", "))
	flag.Parse()
	destDir := getenv("OUTPUT_DIR", "/output")
	if *faultKind != "" {
		if err := publishFault(*faultKind, destDir); err != nil {
			log.Fatalf("publish %s fault: %v", *faultKind, err)
		}
		return
	}
	interval := getenvDuration("INTERVAL_SECONDS", 300)

	log.Printf("starting: dest=%s interval=%s\n", destDir, interval)

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	// Publish once immediately on startup, then on every tick.
	run(destDir)

	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			log.Println("shutting down")
			return
		case <-ticker.C:
			run(destDir)
		}
	}
}

func run(destDir string) {
	transacitons := transaction.GenerateRandomSet(crmAccountIDs()...)
	if err := stageContent(transacitons, destDir); err != nil {
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
// read.
func publishFault(kind, destDir string) error {
	files, err := fault.Build(kind, crmAccountIDs())
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

func crmAccountIDs() []string {
	accountIDs := make([]string, 0)
	for _, accountID := range strings.Split(os.Getenv("CRM_ACCOUNT_IDS"), ",") {
		if accountID = strings.TrimSpace(accountID); accountID != "" {
			accountIDs = append(accountIDs, accountID)
		}
	}
	return accountIDs
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
