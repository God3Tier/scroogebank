// Package documentstest provides an in-memory documents.Store for tests in
// other packages.
package documentstest

import (
	"context"
	"sync"

	"client-profile-management-service/src/internal/documents"
)

// Store keeps objects in memory. When Err is set, every method returns it.
type Store struct {
	mu      sync.Mutex
	objects map[string][]byte
	Err     error
}

func New() *Store {
	return &Store{objects: map[string][]byte{}}
}

func (s *Store) Put(_ context.Context, key string, sealed []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.Err != nil {
		return s.Err
	}
	s.objects[key] = append([]byte(nil), sealed...)
	return nil
}

func (s *Store) Get(_ context.Context, key string) ([]byte, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.Err != nil {
		return nil, s.Err
	}
	sealed, ok := s.objects[key]
	if !ok {
		return nil, documents.ErrObjectNotFound
	}
	return append([]byte(nil), sealed...), nil
}

// Object returns what is stored under key, for assertions.
func (s *Store) Object(key string) ([]byte, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	sealed, ok := s.objects[key]
	return sealed, ok
}
