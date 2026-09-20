package repository

import "testing"

func TestNewClientRepository(t *testing.T) {
	repo := NewClientRepository()
	if repo == nil {
		t.Fatal("NewClientRepository() returned nil")
	}
}
