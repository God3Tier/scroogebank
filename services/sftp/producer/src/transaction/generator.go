package transaction

import (
	"fmt"
	"math"
	"math/rand"
	"strings"
	"time"
	"uuid"
)

// currency is the only one a deployment holds: it serves one legal entity,
// whose accounts are in SGD (Appendix 2, ADR-0038). GenerateRandomSet still
// gives a few rows an unsupported currency on purpose.
const currency = "SGD"

var (
	channels  = []string{"CARD", "ONLINE", "ATM", "TRANSFER"}
	merchants = []string{"NTUC FAIRPRICE", "STARBUCKS", "SHELL", "GRAB", "AMAZON SG"}
	txnTypes  = []string{"DEBIT", "CREDIT"}
	status    = []string{"COMPLETED", "PENDING", "FAILED"}
)

// Generate returns one valid transaction, on one of accountIDs if any are
// given, or on a random external account reference otherwise.
func Generate(accountIDs []string) Transaction {
	return generateTransaction(accountIDs)
}

func generateTransaction(accountIDs []string) Transaction {
	accountID := fmt.Sprintf("ACC-%06d", rand.Intn(999999))
	if len(accountIDs) > 0 {
		accountID = strings.TrimSpace(accountIDs[rand.Intn(len(accountIDs))])
	}
	return Transaction{
		TransactionID:   uuid.New().String(),
		AccountID:       accountID,
		TransferDate:    generateRandomRecent(),
		TransactionType: txnTypes[rand.Intn(100)%2],
		Amount:          generateRandomAmount(),
		Currency:        currency,
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
