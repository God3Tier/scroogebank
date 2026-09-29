package transaction

import (
	"fmt"
	"math"
	"math/rand"
	"time"
	"uuid"
)

var (
	currencies = []string{"SGD", "USD", "EUR", "JPY", "MYR"}
	channels   = []string{"CARD", "ONLINE", "ATM", "TRANSFER"}
	merchants  = []string{"NTUC FAIRPRICE", "STARBUCKS", "SHELL", "GRAB", "AMAZON SG"}
	txnTypes   = []string{"DEBIT", "CREDIT"}
	status     = []string{"COMPLETED", "PENDING", "FAILED"}
)

func generateTransaction() Transaction {
	return Transaction{
		TransactionID:   uuid.New().String(),
		AccountID:       fmt.Sprintf("ACC-%06d", rand.Intn(999999)),
		TransferDate:    generateRandomRecent(),
		TransactionType: txnTypes[rand.Intn(100)%2],
		Amount:          generateRandomAmount(),
		Currency:        currencies[rand.Intn(100)%5],
		Status:          generateStatusRandom(),
		Description:     fmt.Sprintf("%s - %s", channels[rand.Intn(100)%4], merchants[rand.Intn(100)%5]),
	}
}

func generateRandomAmount() float64 {
	weightRand := rand.Float64() * 500
	if rand.Float64() < 0.03 {
		return weightRand * 10000
	}

	return math.Round(weightRand) / 2
}

func generateRandomRecent() time.Time {
	daysAgo := rand.Intn(90)
	return time.Now().AddDate(0, 0, -daysAgo).Add(
		time.Duration(rand.Intn(24)) * time.Hour,
	)
}

func generateStatusRandom() string {
	roll := rand.Float64()
	switch {
	default:
		return status[0]
	case roll < 0.01:
		return status[2]
	case roll < 0.25:
		return status[1]
	}
}
