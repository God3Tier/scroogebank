package main

import (
	"context"
	"errors"
	"testing"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/secretsmanager"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type fakeSecrets map[string]string

func (f fakeSecrets) GetSecretValue(_ context.Context, input *secretsmanager.GetSecretValueInput, _ ...func(*secretsmanager.Options)) (*secretsmanager.GetSecretValueOutput, error) {
	value, ok := f[aws.ToString(input.SecretId)]
	if !ok {
		return nil, errors.New("ResourceNotFoundException")
	}
	return &secretsmanager.GetSecretValueOutput{SecretString: aws.String(value)}, nil
}

func readerOf(f fakeSecrets) func() (secretReader, error) {
	return func() (secretReader, error) { return f, nil }
}

func setSecretEnv(t *testing.T, db, sftpKey, ingest string) {
	t.Helper()
	t.Setenv(dbSecretEnv, db)
	t.Setenv(sftpKeySecretEnv, sftpKey)
	t.Setenv(ingestSecretEnv, ingest)
}

func TestLoadSecretsReadsEachNamedSecret(t *testing.T) {
	setSecretEnv(t, "db-arn", "key-arn", "ingest-arn")
	reader := fakeSecrets{
		"db-arn":     `{"username":"crm_admin","password":"p'a ss\\word"}`,
		"key-arn":    "-----BEGIN OPENSSH PRIVATE KEY-----\n...\n",
		"ingest-arn": `{"client_id":"ingest-client","client_secret":"ingest-secret"}`,
	}

	s, err := loadSecrets(context.Background(), readerOf(reader))

	require.NoError(t, err)
	assert.Equal(t, secrets{
		dbUser: "crm_admin", dbPassword: `p'a ss\word`,
		sftpPrivateKey: []byte("-----BEGIN OPENSSH PRIVATE KEY-----\n...\n"),
		ingestClientID: "ingest-client", ingestClientSecret: "ingest-secret",
	}, s)
}

func TestLoadSecretsNeedsNoSecretsManagerWhenNoneIsNamed(t *testing.T) {
	setSecretEnv(t, "", "", "")

	s, err := loadSecrets(context.Background(), func() (secretReader, error) {
		t.Fatal("Secrets Manager must not be used")
		return nil, nil
	})

	require.NoError(t, err)
	assert.Equal(t, secrets{}, s)
}

func TestLoadSecretsRejectsUnusableSecrets(t *testing.T) {
	tests := map[string]struct {
		db, ingest string
		reader     fakeSecrets
		wantErr    string
	}{
		"missing secret": {
			db: "db-arn", reader: fakeSecrets{},
			wantErr: "read the secret in DB_SECRET_ARN: ResourceNotFoundException",
		},
		"empty secret": {
			db: "db-arn", reader: fakeSecrets{"db-arn": ""},
			wantErr: "the secret in DB_SECRET_ARN has no value",
		},
		"database login not JSON": {
			db: "db-arn", reader: fakeSecrets{"db-arn": "hunter2"},
			wantErr: "the secret in DB_SECRET_ARN must be JSON with a username and a password",
		},
		"database login without a password": {
			db: "db-arn", reader: fakeSecrets{"db-arn": `{"username":"crm_admin"}`},
			wantErr: "the secret in DB_SECRET_ARN must be JSON with a username and a password",
		},
		"client without a secret": {
			ingest: "ingest-arn", reader: fakeSecrets{"ingest-arn": `{"client_id":"ingest-client"}`},
			wantErr: "the secret in COGNITO_INGEST_SECRET must be JSON with a client_id and a client_secret",
		},
	}
	for name, tt := range tests {
		t.Run(name, func(t *testing.T) {
			setSecretEnv(t, tt.db, "", tt.ingest)

			_, err := loadSecrets(context.Background(), readerOf(tt.reader))

			require.EqualError(t, err, tt.wantErr)
			assert.NotContains(t, err.Error(), "hunter2")
		})
	}
}

func TestDatabaseDSNQuotesTheLogin(t *testing.T) {
	for _, env := range []string{"DB_HOST", "DB_PORT", "DB_NAME", "DB_SSLMODE"} {
		t.Setenv(env, "")
	}
	t.Setenv("DB_SSLROOTCERT", "/var/task/rds-ca.pem")

	dsn := databaseDSN(secrets{dbUser: "crm_admin", dbPassword: `p'a ss\word`})

	assert.Equal(t, `host=db port=5432 user='crm_admin' password='p\'a ss\\word' dbname=crm sslmode=disable sslrootcert='/var/task/rds-ca.pem'`, dsn)
}

func TestDatabaseDSNFallsBackToTheEnvironment(t *testing.T) {
	t.Setenv("DB_USER", "local")
	t.Setenv("DB_PASSWORD", "localpw")
	t.Setenv("DB_SSLROOTCERT", "")

	assert.Contains(t, databaseDSN(secrets{}), "user='local' password='localpw'")
}
