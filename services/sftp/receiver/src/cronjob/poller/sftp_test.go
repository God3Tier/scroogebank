package poller

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/pem"
	"errors"
	"net"
	"os"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"golang.org/x/crypto/ssh"
)

// sshServer starts an SSH server on a free local port that accepts only
// allowedKey, and returns its address and host key fingerprint. It opens no
// SFTP subsystem: these tests stop before that.
func sshServer(t *testing.T, allowedKey ssh.PublicKey) (string, string) {
	t.Helper()
	_, hostKey, err := ed25519.GenerateKey(rand.Reader)
	require.NoError(t, err)
	hostSigner, err := ssh.NewSignerFromKey(hostKey)
	require.NoError(t, err)
	config := &ssh.ServerConfig{
		PublicKeyCallback: func(_ ssh.ConnMetadata, key ssh.PublicKey) (*ssh.Permissions, error) {
			if bytes.Equal(key.Marshal(), allowedKey.Marshal()) {
				return nil, nil
			}
			return nil, errors.New("key not allowed")
		},
	}
	config.AddHostKey(hostSigner)

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	t.Cleanup(func() { _ = listener.Close() })
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				serverConn, channels, requests, err := ssh.NewServerConn(conn, config)
				if err != nil {
					return
				}
				defer serverConn.Close()
				go ssh.DiscardRequests(requests)
				for channel := range channels {
					_ = channel.Reject(ssh.Prohibited, "no SFTP in this test")
				}
			}()
		}
	}()
	return listener.Addr().String(), ssh.FingerprintSHA256(hostSigner.PublicKey())
}

// clientKey writes a new private key to a file and returns its path and
// public key.
func clientKey(t *testing.T) (string, ssh.PublicKey) {
	t.Helper()
	_, key, err := ed25519.GenerateKey(rand.Reader)
	require.NoError(t, err)
	block, err := ssh.MarshalPrivateKey(key, "")
	require.NoError(t, err)
	keyPath := filepath.Join(t.TempDir(), "id_ed25519")
	require.NoError(t, os.WriteFile(keyPath, pem.EncodeToMemory(block), 0o600))
	signer, err := ssh.NewSignerFromKey(key)
	require.NoError(t, err)
	return keyPath, signer.PublicKey()
}

func TestConnectSFTPMarksFailuresRetryingCannotFix(t *testing.T) {
	keyPath, publicKey := clientKey(t)
	_, otherKey := clientKey(t)
	badKeyPath := filepath.Join(t.TempDir(), "bad_key")
	require.NoError(t, os.WriteFile(badKeyPath, []byte("not a key"), 0o600))
	closed, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	closedAddress := closed.Addr().String()
	require.NoError(t, closed.Close())

	tests := map[string]struct {
		config        func(address, fingerprint string) Config
		allowed       ssh.PublicKey
		wantPermanent bool
		wantErr       string
	}{
		"host key mismatch": {
			config: func(address, _ string) Config {
				return Config{Address: address, Username: "reportuser", PrivateKeyPath: keyPath, HostKeySHA256: "SHA256:not-the-server"}
			},
			allowed: publicKey, wantPermanent: true, wantErr: "host key fingerprint mismatch",
		},
		"login refused": {
			config: func(address, fingerprint string) Config {
				return Config{Address: address, Username: "reportuser", PrivateKeyPath: keyPath, HostKeySHA256: fingerprint}
			},
			allowed: otherKey, wantPermanent: true, wantErr: "unable to authenticate",
		},
		"private key cannot be parsed": {
			config: func(address, fingerprint string) Config {
				return Config{Address: address, Username: "reportuser", PrivateKeyPath: badKeyPath, HostKeySHA256: fingerprint}
			},
			allowed: publicKey, wantPermanent: true, wantErr: "parse SFTP private key",
		},
		"server unreachable": {
			config: func(_, fingerprint string) Config {
				return Config{Address: closedAddress, Username: "reportuser", PrivateKeyPath: keyPath, HostKeySHA256: fingerprint}
			},
			allowed: publicKey, wantErr: "connect to SFTP server",
		},
		"private key in memory": {
			// The login succeeds with the key, so the next step fails.
			config: func(address, fingerprint string) Config {
				key, err := os.ReadFile(keyPath)
				require.NoError(t, err)
				return Config{Address: address, Username: "reportuser", PrivateKey: key, PrivateKeyPath: badKeyPath, HostKeySHA256: fingerprint}
			},
			allowed: publicKey, wantErr: "start SFTP subsystem",
		},
		"SFTP subsystem refused": {
			config: func(address, fingerprint string) Config {
				return Config{Address: address, Username: "reportuser", PrivateKeyPath: keyPath, HostKeySHA256: fingerprint}
			},
			allowed: publicKey, wantErr: "start SFTP subsystem",
		},
	}

	for name, tt := range tests {
		t.Run(name, func(t *testing.T) {
			address, fingerprint := sshServer(t, tt.allowed)
			p := New(tt.config(address, fingerprint), &fakeStore{}, &fakeDirectory{})

			_, _, err := p.connectSFTP(context.Background())

			require.Error(t, err)
			assert.Contains(t, err.Error(), tt.wantErr)
			assert.Equal(t, tt.wantPermanent, isPermanent(err))
		})
	}
}
