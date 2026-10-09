package poller

import (
	"context"
	"crypto/subtle"
	"fmt"
	"net"
	"os"
	"time"

	"github.com/pkg/sftp"
	"golang.org/x/crypto/ssh"
)

func (p *Poller) connectSFTP(ctx context.Context) (remoteSFTP, func(), error) {
	privateKey, err := os.ReadFile(p.config.PrivateKey)
	if err != nil {
		return nil, nil, fmt.Errorf("read SFTP private key: %w", err)
	}
	signer, err := ssh.ParsePrivateKey(privateKey)
	if err != nil {
		return nil, nil, fmt.Errorf("parse SFTP private key: %w", err)
	}

	callback := func(_ string, _ net.Addr, key ssh.PublicKey) error {
		actual := ssh.FingerprintSHA256(key)
		if subtle.ConstantTimeCompare([]byte(actual), []byte(p.config.HostKeySHA256)) != 1 {
			return fmt.Errorf("SFTP host key fingerprint mismatch: got %s", actual)
		}
		return nil
	}
	sshConfig := &ssh.ClientConfig{
		User: p.config.Username, Auth: []ssh.AuthMethod{ssh.PublicKeys(signer)},
		HostKeyCallback: callback, Timeout: 10 * time.Second,
	}

	dialer := net.Dialer{Timeout: 10 * time.Second}
	connection, err := dialer.DialContext(ctx, "tcp", p.config.Address)
	if err != nil {
		return nil, nil, fmt.Errorf("connect to SFTP server %q: %w", p.config.Address, err)
	}
	if err := connection.SetDeadline(time.Now().Add(10 * time.Second)); err != nil {
		_ = connection.Close()
		return nil, nil, fmt.Errorf("set SFTP connection deadline: %w", err)
	}
	sshConnection, channels, requests, err := ssh.NewClientConn(connection, p.config.Address, sshConfig)
	if err != nil {
		_ = connection.Close()
		return nil, nil, fmt.Errorf("establish SSH session: %w", err)
	}
	_ = connection.SetDeadline(time.Time{})
	sshClient := ssh.NewClient(sshConnection, channels, requests)
	sftpClient, err := sftp.NewClient(sshClient)
	if err != nil {
		_ = sshClient.Close()
		return nil, nil, fmt.Errorf("start SFTP subsystem: %w", err)
	}
	closeClient := func() {
		_ = sftpClient.Close()
		_ = sshClient.Close()
	}
	return sftpAdapter{Client: sftpClient}, closeClient, nil
}

type sftpAdapter struct{ *sftp.Client }

func (c sftpAdapter) Open(name string) (remoteFile, error) { return c.Client.Open(name) }
