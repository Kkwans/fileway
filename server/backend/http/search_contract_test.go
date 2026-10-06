package fbhttp

import (
	"context"
	"testing"
)

func TestBoundedPositiveInt(t *testing.T) {
	if got := boundedPositiveInt("", 1000, 1000); got != 1000 {
		t.Fatalf("default = %d", got)
	}
	if got := boundedPositiveInt("2000", 1000, 1000); got != 1000 {
		t.Fatalf("bounded value = %d", got)
	}
	if got := boundedPositiveInt("250", 1000, 1000); got != 250 {
		t.Fatalf("explicit value = %d", got)
	}
}

func TestRecentSearchCacheIsBounded(t *testing.T) {
	recentSearches.Lock()
	recentSearches.items = make(map[string]cachedSearch)
	recentSearches.Unlock()
	t.Cleanup(func() {
		recentSearches.Lock()
		recentSearches.items = make(map[string]cachedSearch)
		recentSearches.Unlock()
	})

	for index := 0; index < searchCacheEntries+5; index++ {
		storeCachedSearch(string(rune(index)), nil, "completed")
	}
	recentSearches.Lock()
	count := len(recentSearches.items)
	recentSearches.Unlock()
	if count != searchCacheEntries {
		t.Fatalf("cache entries = %d, want %d", count, searchCacheEntries)
	}

	storeCachedSearch("too-large", make([]searchResult, maxCachedSearchResults+1), "completed")
	if _, exists := loadCachedSearch("too-large"); exists {
		t.Fatal("oversized search must not be cached")
	}
}

func TestSendSearchEventStopsWhenClientIsCanceled(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if sendSearchEvent(ctx, make(chan searchStreamEvent), searchStreamEvent{Type: "summary"}) {
		t.Fatal("canceled client must not accept a summary event")
	}
}
