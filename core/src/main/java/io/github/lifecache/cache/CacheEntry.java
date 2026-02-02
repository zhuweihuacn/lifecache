package io.github.lifecache.cache;

/**
 * Cache entry with value and timestamp.
 * 
 * @param <T> the type of the cached value
 */
public record CacheEntry<T>(
    T value,
    long timestampMs
) {
    /**
     * Create a new cache entry with current timestamp.
     */
    public static <T> CacheEntry<T> of(T value) {
        return new CacheEntry<>(value, System.currentTimeMillis());
    }
    
    /**
     * Check if this entry is expired based on the given TTL.
     * 
     * @param softTtlMs the soft TTL in milliseconds
     * @return true if the entry is older than the TTL
     */
    public boolean isExpired(long softTtlMs) {
        return System.currentTimeMillis() - timestampMs > softTtlMs;
    }
    
    /**
     * Get the age of this entry in milliseconds.
     */
    public long ageMs() {
        return System.currentTimeMillis() - timestampMs;
    }
}
