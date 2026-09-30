package models

type AccountType string

const (
	AccountTypeChecking   AccountType = "CHECKING"
	AccountTypeSavings    AccountType = "SAVINGS"
	AccountTypeCredit     AccountType = "CREDIT"
	AccountTypeLoan       AccountType = "LOAN"
	AccountTypeInvestment AccountType = "INVESTMENT"
)

func (a AccountType) IsValid() bool {
	switch a {
	case AccountTypeChecking, AccountTypeSavings, AccountTypeCredit, AccountTypeLoan, AccountTypeInvestment:
		return true
	}
	return false
}
