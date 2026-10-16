package encryption

import (
	"sync"
	"time"
)

const (
	// keyCacheTTL is how long a plaintext data key stays in memory after it
	// was fetched, limiting KMS calls on repeated reads (ADR-0030 decision 3).
	keyCacheTTL = 5 * time.Minute
	// keyCacheSize bounds how many clients' keys are held at once.
	keyCacheSize = 1000
)

type cacheEntry struct {
	key     DataKey
	expires time.Time
}

type keyCache struct {
	mu      sync.Mutex
	ttl     time.Duration
	size    int
	now     func() time.Time
	entries map[string]cacheEntry
}

func newKeyCache(ttl time.Duration, size int, now func() time.Time) *keyCache {
	return &keyCache{ttl: ttl, size: size, now: now, entries: map[string]cacheEntry{}}
}

func (c *keyCache) get(clientID string) (DataKey, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	entry, ok := c.entries[clientID]
	if !ok {
		return DataKey{}, false
	}
	if !c.now().Before(entry.expires) {
		delete(c.entries, clientID)
		return DataKey{}, false
	}
	return entry.key, true
}

func (c *keyCache) put(clientID string, key DataKey) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if _, ok := c.entries[clientID]; !ok && len(c.entries) >= c.size {
		c.evictLocked()
	}
	c.entries[clientID] = cacheEntry{key: key, expires: c.now().Add(c.ttl)}
}

func (c *keyCache) remove(clientID string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.entries, clientID)
}

// evictLocked drops every expired entry, or one arbitrary entry if none has
// expired.
func (c *keyCache) evictLocked() {
	now := c.now()
	for id, entry := range c.entries {
		if !now.Before(entry.expires) {
			delete(c.entries, id)
		}
	}
	if len(c.entries) < c.size {
		return
	}
	for id := range c.entries {
		delete(c.entries, id)
		return
	}
}
