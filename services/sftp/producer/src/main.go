package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"producer/src/transaction"
	"strconv"
	"strings"
	"syscall"
	"time"
)

func main() {
	destDir := getenv("OUTPUT_DIR", "/output")
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
	accountIDs := make([]string, 0)
	for _, accountID := range strings.Split(os.Getenv("CRM_ACCOUNT_IDS"), ",") {
		if accountID = strings.TrimSpace(accountID); accountID != "" {
			accountIDs = append(accountIDs, accountID)
		}
	}
	transacitons := transaction.GenerateRandomSet(accountIDs...)
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
	buf := make([]byte, 4)
	if _, err := rand.Read(buf); err != nil {
		return err
	}
	uniq := hex.EncodeToString(buf)
	ts := time.Now().UTC().Format("20060102T150405Z")
	final := filepath.Join(destDir, fmt.Sprintf("report-%s-%s.json", ts, uniq))
	tmp := final + ".tmp"

	out, err := os.Create(tmp)
	if err != nil {
		return err
	}

	if _, err := out.Write(content); err != nil {
		out.Close()
		os.Remove(tmp)
		return err
	}

	if err := out.Close(); err != nil {
		os.Remove(tmp)
		return err
	}

	return os.Rename(tmp, final)
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
