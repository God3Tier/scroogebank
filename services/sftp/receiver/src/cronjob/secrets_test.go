package main

import (
	"context"
	"errors"
	"testing"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/secretsmanager"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/require"
)

// expectSecret makes reader return value for the secret id, once.
func expectSecret(reader *MockSecretReader, id, value string) {
	reader.EXPECT().
		GetSecretValue(mock.Anything, &secretsmanager.GetSecretValueInput{SecretId: aws.String(id)}).
		Return(&secretsmanager.GetSecretValueOutput{SecretString: aws.String(value)}, nil).
		Once()
}

func readerOf(reader SecretReader) func() (SecretReader, error) {
	return func() (SecretReader, error) { return reader, nil }
}

func setSecretEnv(t *testing.T, db, sftpKey, ingest string) {
	t.Helper()
	t.Setenv(dbSecretEnv, db)
	t.Setenv(sftpKeySecretEnv, sftpKey)
	t.Setenv(ingestSecretEnv, ingest)
}

func TestLoadSecretsReadsEachNamedSecret(t *testing.T) {
	setSecretEnv(t, "db-arn", "key-arn", "ingest-arn")
	reader := NewMockSecretReader(t)
	expectSecret(reader, "db-arn", `{"username":"crm_admin","password":"p'a ss\\word"}`)
	expectSecret(reader, "key-arn", "-----BEGIN OPENSSH PRIVATE KEY-----\n...\n")
	expectSecret(reader, "ingest-arn", `{"client_id":"ingest-client","client_secret":"ingest-secret"}`)

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

	s, err := loadSecrets(context.Background(), func() (SecretReader, error) {
		t.Fatal("Secrets Manager must not be used")
		return nil, nil
	})

	require.NoError(t, err)
	assert.Equal(t, secrets{}, s)
}

func TestLoadSecretsReportsAMissingSecret(t *testing.T) {
	setSecretEnv(t, "db-arn", "key-arn", "")
	reader := NewMockSecretReader(t)
	reader.EXPECT().
		GetSecretValue(mock.Anything, &secretsmanager.GetSecretValueInput{SecretId: aws.String("db-arn")}).
		Return(nil, errors.New("ResourceNotFoundException")).
		Once()

	_, err := loadSecrets(context.Background(), readerOf(reader))

	// The SSH key is not read once the database login has failed.
	require.EqualError(t, err, "read the secret in DB_SECRET_ARN: ResourceNotFoundException")
}

func TestLoadSecretsRejectsUnusableSecrets(t *testing.T) {
	tests := map[string]struct {
		db, ingest string
		id, value  string
		wantErr    string
	}{
		"empty secret": {
			db: "db-arn", id: "db-arn", value: "",
			wantErr: "the secret in DB_SECRET_ARN has no value",
		},
		"database login not JSON": {
			db: "db-arn", id: "db-arn", value: "hunter2",
			wantErr: "the secret in DB_SECRET_ARN must be JSON with a username and a password",
		},
		"database login without a password": {
			db: "db-arn", id: "db-arn", value: `{"username":"crm_admin"}`,
			wantErr: "the secret in DB_SECRET_ARN must be JSON with a username and a password",
		},
		"client without a secret": {
			ingest: "ingest-arn", id: "ingest-arn", value: `{"client_id":"ingest-client"}`,
			wantErr: "the secret in COGNITO_INGEST_SECRET must be JSON with a client_id and a client_secret",
		},
	}
	for name, tt := range tests {
		t.Run(name, func(t *testing.T) {
			setSecretEnv(t, tt.db, "", tt.ingest)
			reader := NewMockSecretReader(t)
			expectSecret(reader, tt.id, tt.value)

			_, err := loadSecrets(context.Background(), readerOf(reader))

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
