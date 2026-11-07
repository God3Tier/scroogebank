package security

import (
	"testing"
	"time"
)

func TestServiceClaimsRequireConfiguredClientExactScopeAndNoUserRole(t *testing.T) {
	now := time.Now()
	verifier := &CognitoVerifier{
		issuer:        "https://cognito-idp.ap-southeast-1.amazonaws.com/pool",
		clientID:      "health-client",
		requiredScope: "clients.assignment.read",
		serviceOnly:   true,
	}
	valid := cognitoClaims{Issuer: verifier.issuer, Subject: "health-client",
		ClientID: verifier.clientID, TokenUse: "access", Scope: verifier.requiredScope,
		IssuedAt: now.Add(-time.Minute).Unix(), Expires: now.Add(time.Minute).Unix()}

	if _, err := verifier.principal(valid, now); err != nil {
		t.Fatalf("valid machine claims rejected: %v", err)
	}
	for name, mutate := range map[string]func(*cognitoClaims){
		"wrong client": func(c *cognitoClaims) { c.ClientID = "interactive-client" },
		"wrong scope":  func(c *cognitoClaims) { c.Scope = "clients.read" },
		"extra scope":  func(c *cognitoClaims) { c.Scope += " clients.read" },
		"Agent token":  func(c *cognitoClaims) { c.Groups = []string{"AGENT"} },
		"Admin token":  func(c *cognitoClaims) { c.Groups = []string{"ADMIN"} },
	} {
		t.Run(name, func(t *testing.T) {
			claims := valid
			mutate(&claims)
			if _, err := verifier.principal(claims, now); err == nil {
				t.Fatal("invalid machine claims accepted")
			}
		})
	}
}
