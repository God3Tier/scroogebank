package models

// Caller is the authenticated user a request acts for, taken from the verified
// Cognito token. Subject is the token's sub.
type Caller struct {
	Subject string
	Admin   bool
}
