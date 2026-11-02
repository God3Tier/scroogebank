package encryption

import (
	"sync"
	"time"
)

const (
	// keyCacheTTL is how long a plaintext data key stays in memory after it
	// was fetched, limiting KMS calls on repeated reads (ADR-0030 decision 3).
	keyCacheTTL = 5 * time.Minute
	// keyCacheSize bounds how many clients' keys are held at once. It covers
	// every client one task serves at the load test's 10,000 (ADR-0072): at
	// 1,000, 100 agents' first pages alone missed the cache and listing
	// clients waited on DynamoDB and KMS. A key is 32 bytes, so the cache
	// stays under a few megabytes.
	keyCacheSize = 10000
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

// evictLocked drops every expired entry, or, if none has expired, the entry
// that expires first: the key fetched longest ago, which is the least likely
// to be read again (ADR-0072).
func (c *keyCache) evictLocked() {
	now := c.now()
	oldestID, oldest := "", time.Time{}
	for id, entry := range c.entries {
		if !now.Before(entry.expires) {
			delete(c.entries, id)
			continue
		}
		if oldestID == "" || entry.expires.Before(oldest) {
			oldestID, oldest = id, entry.expires
		}
	}
	if len(c.entries) >= c.size && oldestID != "" {
		delete(c.entries, oldestID)
	}
}
