package main

import (
	"context"
	"encoding/json"
	"fmt"
	"os"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/secretsmanager"
)

// Deployed as a Lambda function, the import reads its database login, SSH
// private key and Cognito client secret from Secrets Manager when it starts,
// never from environment variables (ADR-0024 security). Each of these
// variables names a secret. When one is unset, the value comes from the
// environment instead, as when the poller runs locally.
const (
	dbSecretEnv      = "DB_SECRET_ARN"           // RDS-managed: {"username", "password"}
	sftpKeySecretEnv = "SFTP_PRIVATE_KEY_SECRET" // the OpenSSH private key
	ingestSecretEnv  = "COGNITO_INGEST_SECRET"   // {"client_id", "client_secret"}
)

type secrets struct {
	dbUser, dbPassword                 string
	sftpPrivateKey                     []byte
	ingestClientID, ingestClientSecret string
}

type secretReader interface {
	GetSecretValue(context.Context, *secretsmanager.GetSecretValueInput, ...func(*secretsmanager.Options)) (*secretsmanager.GetSecretValueOutput, error)
}

// loadSecrets reads the secrets the environment names. It calls newReader only
// when at least one is named.
func loadSecrets(ctx context.Context, newReader func() (secretReader, error)) (secrets, error) {
	var reader secretReader
	// read returns "" when env is unset.
	read := func(env string) (string, error) {
		id := os.Getenv(env)
		if id == "" {
			return "", nil
		}
		if reader == nil {
			var err error
			if reader, err = newReader(); err != nil {
				return "", err
			}
		}
		out, err := reader.GetSecretValue(ctx, &secretsmanager.GetSecretValueInput{SecretId: aws.String(id)})
		if err != nil {
			return "", fmt.Errorf("read the secret in %s: %w", env, err)
		}
		if out.SecretString == nil || *out.SecretString == "" {
			return "", fmt.Errorf("the secret in %s has no value", env)
		}
		return *out.SecretString, nil
	}

	var s secrets
	value, err := read(dbSecretEnv)
	if err != nil {
		return secrets{}, err
	}
	if value != "" {
		var login struct {
			Username string `json:"username"`
			Password string `json:"password"`
		}
		// The JSON error is left out: it can quote part of the secret.
		if json.Unmarshal([]byte(value), &login) != nil || login.Username == "" || login.Password == "" {
			return secrets{}, fmt.Errorf("the secret in %s must be JSON with a username and a password", dbSecretEnv)
		}
		s.dbUser, s.dbPassword = login.Username, login.Password
	}

	if value, err = read(sftpKeySecretEnv); err != nil {
		return secrets{}, err
	}
	if value != "" {
		s.sftpPrivateKey = []byte(value)
	}

	if value, err = read(ingestSecretEnv); err != nil {
		return secrets{}, err
	}
	if value != "" {
		var client struct {
			ClientID     string `json:"client_id"`
			ClientSecret string `json:"client_secret"`
		}
		if json.Unmarshal([]byte(value), &client) != nil || client.ClientID == "" || client.ClientSecret == "" {
			return secrets{}, fmt.Errorf("the secret in %s must be JSON with a client_id and a client_secret", ingestSecretEnv)
		}
		s.ingestClientID, s.ingestClientSecret = client.ClientID, client.ClientSecret
	}
	return s, nil
}
