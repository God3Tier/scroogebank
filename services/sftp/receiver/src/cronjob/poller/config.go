package poller

import (
	"fmt"
	"os"
	"time"
)

type Config struct {
	Address  string
	Username string
	// PrivateKey is the SSH private key, when it is already in memory, such
	// as the Lambda function's, read from Secrets Manager (ADR-0024
	// security). Otherwise each connection reads it from PrivateKeyPath.
	PrivateKey     []byte
	PrivateKeyPath string
	HostKeySHA256  string
	RemoteDir      string
	Interval       time.Duration
}

// LoadConfig reads the configuration from the environment. privateKey, when
// given, is used instead of the file at SFTP_PRIVATE_KEY_PATH.
func LoadConfig(privateKey []byte) (Config, error) {
	interval, err := time.ParseDuration(envOrDefault("SFTP_POLL_INTERVAL", "30s"))
	if err != nil || interval <= 0 {
		return Config{}, fmt.Errorf("SFTP_POLL_INTERVAL must be a positive duration, such as 30s or 5m")
	}

	cfg := Config{
		Address:        envOrDefault("SFTP_ADDR", "sftp:22"),
		Username:       envOrDefault("SFTP_USER", "reportuser"),
		PrivateKey:     privateKey,
		PrivateKeyPath: os.Getenv("SFTP_PRIVATE_KEY_PATH"),
		HostKeySHA256:  os.Getenv("SFTP_HOST_KEY_FINGERPRINT"),
		RemoteDir:      envOrDefault("SFTP_REMOTE_DIR", "/reports"),
		Interval:       interval,
	}
	if len(cfg.PrivateKey) == 0 && cfg.PrivateKeyPath == "" {
		return Config{}, fmt.Errorf("SFTP_PRIVATE_KEY_PATH is required")
	}
	if cfg.HostKeySHA256 == "" {
		return Config{}, fmt.Errorf("SFTP_HOST_KEY_FINGERPRINT is required")
	}
	return cfg, nil
}
