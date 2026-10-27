package transaction

import (
	"fmt"
	"math"
	"math/rand"
	"time"
	"uuid"

	"producer/src/accounts"
)

var (
	channels  = []string{"CARD", "ONLINE", "ATM", "TRANSFER"}
	merchants = []string{"NTUC FAIRPRICE", "STARBUCKS", "SHELL", "GRAB", "AMAZON SG"}
	txnTypes  = []string{"DEBIT", "CREDIT"}
	status    = []string{"COMPLETED", "PENDING", "FAILED"}
)

// Generate returns one valid transaction, on one of list if any are given,
// in that account's currency. Otherwise it is on a random external account
// reference, in accounts.DefaultCurrency. GenerateRandomSet still gives a
// few rows an unsupported currency on purpose.
func Generate(list []accounts.Account) Transaction {
	return generateTransaction(list)
}

func generateTransaction(list []accounts.Account) Transaction {
	account := accounts.Account{ID: fmt.Sprintf("ACC-%06d", rand.Intn(999999)), Currency: accounts.DefaultCurrency}
	if len(list) > 0 {
		account = list[rand.Intn(len(list))]
	}
	return Transaction{
		TransactionID:   uuid.New().String(),
		AccountID:       account.ID,
		TransferDate:    generateRandomRecent(),
		TransactionType: txnTypes[rand.Intn(100)%2],
		Amount:          generateRandomAmount(),
		Currency:        account.Currency,
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
