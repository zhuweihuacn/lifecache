package io.github.lifecache.cache;

import io.github.lifecache.Lifecache;

import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Local cache with adaptive soft TTL based on system health.
 * 
 * <p>When system latency is high, the soft TTL extends automatically (up to maxTtl),
 * allowing more cache hits and reducing backend load.</p>
 * 
 * <p><b>Note:</b> Fallback logic is NOT part of cache. Use {@link Lifecache#shouldDrop(String)}
 * separately before calling the cache.</p>
 * 
 * <p>Supports optional max size with LRU-like eviction (oldest entries first).</p>
 * 
 * <h2>Usage</h2>
 * <pre>{@code
 * // Configure Lifecache with your breakdown names
 * Lifecache lifecache = Lifecache.builder()
 *     .breakdown("cacheTtl", Lifecache.linear(Duration.ofSeconds(10), Duration.ofMinutes(30)))
 *     .breakdown("loadShedding", Lifecache.dropRate(0.7, 0.9))
 *     .build();
 * 
 * // Create cache with breakdown name
 * LocalCache<String> cache = new LocalCache<>(lifecache, "cacheTtl", Duration.ofHours(1));
 * 
 * // Check fallback separately
 * if (lifecache.shouldDrop("loadShedding")) {
 *     return FALLBACK_VALUE;
 * }
 * 
 * // Use cache
 * CacheResult<String> result = cache.get(key, () -> backendCall());
 * }</pre>
 * 
 * @param <V> the type of cached values
 */
public class LocalCache<V> {
    
    private final Lifecache lifecache;
    private final String stalenessBreakdown;
    private final ConcurrentMap<String, CacheEntry<V>> cache;
    private final Duration maxTtl;
    private final int maxSize;
    
    /**
     * Create a new local cache with size limit.
     * 
     * @param lifecache the Lifecache instance for adaptive TTL
     * @param stalenessBreakdown the breakdown name for staleness/TTL
     * @param maxTtl maximum TTL (cap for adaptive TTL)
     * @param maxSize maximum number of entries (0 = unlimited)
     */
    public LocalCache(Lifecache lifecache, String stalenessBreakdown, Duration maxTtl, int maxSize) {
        this.lifecache = lifecache;
        this.stalenessBreakdown = stalenessBreakdown;
        this.cache = new ConcurrentHashMap<>();
        this.maxTtl = maxTtl;
        this.maxSize = maxSize;
    }
    
    /**
     * Create a new local cache without size limit.
     * 
     * @param lifecache the Lifecache instance for adaptive TTL
     * @param stalenessBreakdown the breakdown name for staleness/TTL
     * @param maxTtl maximum TTL (cap for adaptive TTL)
     */
    public LocalCache(Lifecache lifecache, String stalenessBreakdown, Duration maxTtl) {
        this(lifecache, stalenessBreakdown, maxTtl, 0);
    }
    
    /**
     * Create a new local cache with 1 hour max TTL and no size limit.
     * 
     * @param lifecache the Lifecache instance for adaptive TTL
     * @param stalenessBreakdown the breakdown name for staleness/TTL
     */
    public LocalCache(Lifecache lifecache, String stalenessBreakdown) {
        this(lifecache, stalenessBreakdown, Duration.ofHours(1), 0);
    }
    
    /**
     * Get value from cache or compute if missing/expired.
     * 
     * <p>Flow:</p>
     * <ol>
     *   <li>Check if cache entry exists and is within soft TTL</li>
     *   <li>If yes: return cached value (no write)</li>
     *   <li>If no: compute value, write to cache with timestamp, return</li>
     * </ol>
     * 
     * <p><b>Note:</b> This method does NOT handle fallback. Check {@link Lifecache#shouldFallback()}
     * before calling this method if you want fallback behavior.</p>
     * 
     * @param key the cache key
     * @param loader function to compute value on cache miss
     * @return CacheResult containing the value and metadata
     */
    public CacheResult<V> get(String key, Supplier<V> loader) {
        // Get current soft TTL based on system health
        Duration softTtl = lifecache.getBreakdown(stalenessBreakdown);
        long softTtlMs = Math.min(softTtl.toMillis(), maxTtl.toMillis());
        
        // Check cache
        CacheEntry<V> entry = cache.get(key);
        
        if (entry != null && !entry.isExpired(softTtlMs)) {
            // Cache hit - return cached value, don't update cache
            return CacheResult.hit(key, entry.value(), entry.timestampMs(), softTtlMs);
        }
        
        // Cache miss or expired - compute new value
        V value = loader.get();
        
        // Evict if necessary before adding new entry
        if (maxSize > 0 && cache.size() >= maxSize) {
            evictOldest(softTtlMs);
        }
        
        // Write to cache with current timestamp
        CacheEntry<V> newEntry = CacheEntry.of(value);
        cache.put(key, newEntry);
        
        return CacheResult.miss(key, value, newEntry.timestampMs(), softTtlMs);
    }
    
    /**
     * Evict oldest entries to make room for new ones.
     * First removes expired entries, then oldest by timestamp.
     */
    private void evictOldest(long softTtlMs) {
        // First, try to remove expired entries
        cache.entrySet().removeIf(e -> e.getValue().isExpired(softTtlMs));
        
        // If still over limit, remove oldest entries (by timestamp)
        while (cache.size() >= maxSize) {
            cache.entrySet().stream()
                .min(Comparator.comparingLong(e -> e.getValue().timestampMs()))
                .ifPresent(oldest -> cache.remove(oldest.getKey()));
        }
    }
    
    /**
     * Get value from cache without computing (peek only).
     * 
     * @param key the cache key
     * @return the cache entry, or null if not found
     */
    public CacheEntry<V> peek(String key) {
        return cache.get(key);
    }
    
    /**
     * Check if a key is cached and within current soft TTL.
     */
    public boolean isCached(String key) {
        CacheEntry<V> entry = cache.get(key);
        if (entry == null) return false;
        
        long softTtlMs = Math.min(lifecache.<Duration>getBreakdown(stalenessBreakdown).toMillis(), maxTtl.toMillis());
        return !entry.isExpired(softTtlMs);
    }
    
    /**
     * Manually put a value into the cache.
     */
    public void put(String key, V value) {
        if (maxSize > 0 && cache.size() >= maxSize) {
            long softTtlMs = Math.min(lifecache.<Duration>getBreakdown(stalenessBreakdown).toMillis(), maxTtl.toMillis());
            evictOldest(softTtlMs);
        }
        cache.put(key, CacheEntry.of(value));
    }
    
    /**
     * Invalidate a cache entry.
     */
    public void invalidate(String key) {
        cache.remove(key);
    }
    
    /**
     * Clear all cache entries.
     */
    public void clear() {
        cache.clear();
    }
    
    /**
     * Get the current number of cached entries.
     */
    public int size() {
        return cache.size();
    }
    
    /**
     * Get the max size limit (0 = unlimited).
     */
    public int maxSize() {
        return maxSize;
    }
    
    /**
     * Get current soft TTL based on system health.
     */
    public Duration getCurrentSoftTtl() {
        Duration softTtl = lifecache.getBreakdown(stalenessBreakdown);
        return softTtl.compareTo(maxTtl) < 0 ? softTtl : maxTtl;
    }
    
    /**
     * Result of a cache operation.
     */
    public record CacheResult<V>(
        String key,
        V value,
        long timestampMs,
        long softTtlMs,
        boolean fromCache
    ) {
        public boolean isFromCache() {
            return fromCache;
        }
        
        public boolean isMiss() {
            return !fromCache;
        }
        
        static <V> CacheResult<V> hit(String key, V value, long timestampMs, long softTtlMs) {
            return new CacheResult<>(key, value, timestampMs, softTtlMs, true);
        }
        
        static <V> CacheResult<V> miss(String key, V value, long timestampMs, long softTtlMs) {
            return new CacheResult<>(key, value, timestampMs, softTtlMs, false);
        }
    }
}
