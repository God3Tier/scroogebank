package serverd

import (
	"fmt"
	"os"
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestGetHTTPPort(t *testing.T) {
	tests := map[string]struct {
		env  string
		want int
	}{
		"valid port":         {"9090", 9090},
		"empty falls back":   {"", defaultPort},
		"invalid falls back": {"not-a-number", defaultPort},
		"out of range":       {"70000", defaultPort},
	}
	for name, tt := range tests {
		t.Run(name, func(t *testing.T) {
			t.Setenv("HTTP_PORT", tt.env)
			assert.Equal(t, tt.want, getHTTPPort())
		})
	}
}

func buildDSN() string {
	return fmt.Sprintf("dbname=%s host=%s user=%s password=%s sslmode=%s",
		os.Getenv("DB_NAME"), os.Getenv("DB_HOST"), os.Getenv("DB_USER"),
		os.Getenv("DB_PASSWORD"), os.Getenv("SSLMODE"))
}

func TestBuildDSN(t *testing.T) {
	t.Setenv("DB_NAME", "testdb")
	t.Setenv("DB_HOST", "localhost")
	t.Setenv("DB_USER", "postgres")
	t.Setenv("DB_PASSWORD", "secret")
	t.Setenv("SSLMODE", "disable")

	want := "dbname=testdb host=localhost user=postgres password=secret sslmode=disable"
	assert.Equal(t, want, buildDSN())
}
