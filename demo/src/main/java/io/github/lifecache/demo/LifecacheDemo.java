package io.github.lifecache.demo;

import io.github.lifecache.Lifecache;
import io.github.lifecache.cache.LocalCache;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 🚢 Lifecache Demo - Thundering Herd Simulation with Fallback Strategy
 * 
 * <p>Demonstrates adaptive caching with <b>separate</b> fallback under thundering herd conditions:</p>
 * <ul>
 *   <li>Runs for 5 minutes</li>
 *   <li>Every 20 seconds: 10,000 request burst (thundering herd)</li>
 *   <li>During burst: high latency → TTL extends, fallback kicks in</li>
 *   <li><b>Fallback is SEPARATE from cache</b> - when latency is high, skip backend call entirely</li>
 *   <li>Fallback returns static value (-1) without hitting backend or cache</li>
 *   <li>Between bursts: latency recovers → TTL shortens, fallback stops</li>
 * </ul>
 * 
 * <h2>Design Pattern</h2>
 * <pre>{@code
 * // 1. First check if should fallback (independent of cache)
 * if (lifecache.shouldDrop("loadShedding")) {
 *     return FALLBACK_VALUE;  // Don't hit backend or cache
 * }
 * 
 * // 2. Then use cache normally
 * return cache.get(key, () -> backendCall());
 * }</pre>
 */
public class LifecacheDemo {
    
    private static final Random random = new Random();
    
    // Cumulative stats
    private static final AtomicInteger totalRequests = new AtomicInteger(0);
    private static final AtomicLong startTime = new AtomicLong();
    private static final AtomicInteger thunderingHerdCount = new AtomicInteger(0);
    
    // Per-window stats (reset after each print)
    private static final AtomicInteger windowRequests = new AtomicInteger(0);
    private static final AtomicInteger windowHits = new AtomicInteger(0);
    private static final AtomicInteger windowMisses = new AtomicInteger(0);
    private static final AtomicInteger windowFallbacks = new AtomicInteger(0);
    
    // Concurrency-based latency simulator
    private static final ConcurrencyLatencySimulator latencySimulator = new ConcurrencyLatencySimulator();
    
    // Background load with normal distribution (mean=100, range 0-500)
    private static volatile int currentRps = 100;
    
    public static void main(String[] args) throws InterruptedException {
        System.out.println("🚢 ========================================");
        System.out.println("🚢 Lifecache - Thundering Herd Demo");
        System.out.println("🚢 ========================================");
        System.out.println("⚡ Simulating thundering herd every 20 seconds");
        System.out.println("📊 Background: ~100 RPS (normal distribution 0-500)");
        System.out.println("🌊 Thundering herd: 10,000 requests burst");
        System.out.println("⏱️  Demo runs for 5 minutes\n");
        
        // Create Lifecache with user-defined breakdowns
        Lifecache lifecache = Lifecache.builder()
            // Latency → Health score
            .latencyThreshold(120, 1.0)             // < 120ms = healthy
            .latencyThreshold(200, 0.75)            // 200ms = degraded
            .latencyThreshold(400, 0.5)             // 400ms = degraded
            .latencyThreshold(600, 0.25)            // 600ms = stressed
            .latencyThreshold(800, 0.0)             // > 800ms = critical
            // Health score → Cache TTL (step function)
            .breakdown("cacheTtl", Lifecache.stepFunction(
                Lifecache.entry(1.0,  Duration.ofSeconds(10)),   // healthy → 10s
                Lifecache.entry(0.75, Duration.ofMinutes(1)),    // degraded → 1min
                Lifecache.entry(0.5,  Duration.ofMinutes(2)),    // degraded → 2min
                Lifecache.entry(0.25, Duration.ofMinutes(3)),    // stressed → 3min
                Lifecache.entry(0.0,  Duration.ofMinutes(5))     // critical → 5min
            ))
            // Health score → Drop rate (aggressive!)
            // health < 0.95 (P95 > 120ms) → start dropping immediately
            // health = 0.0 → drop up to 95%
            .breakdown("loadShedding", Lifecache.dropRate(0.95, 0.95))
            .metricsWindow(Duration.ofSeconds(5))
            .build();
        
        // Create LocalCache with breakdown name and max TTL
        LocalCache<String> cache = new LocalCache<>(lifecache, "cacheTtl", Duration.ofMinutes(5));
        
        startTime.set(System.currentTimeMillis());
        
        // Thread pool for concurrent requests (large for thundering herd)
        ExecutorService requestExecutor = Executors.newFixedThreadPool(500);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
        
        // Background load generator - normal distribution (mean=100, stddev=100, range 0-500)
        scheduler.scheduleAtFixedRate(() -> {
            // Update RPS every 100ms with normal distribution
            currentRps = generateNormalRps(100, 100, 0, 500);
            int batchSize = Math.max(1, currentRps / 10);
            for (int i = 0; i < batchSize; i++) {
                final int requestId = totalRequests.incrementAndGet();
                requestExecutor.submit(() -> {
                    try {
                        simulateRequest(lifecache, cache, requestId);
                    } catch (Exception e) {
                        // Ignore
                    }
                });
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
        
        // Thundering herd - 10,000 requests every 20 seconds
        scheduler.scheduleAtFixedRate(() -> {
            triggerThunderingHerd(lifecache, cache, requestExecutor);
        }, 25, 20, TimeUnit.SECONDS);
        
        // Stats printer - every 1 second
        scheduler.scheduleAtFixedRate(() -> {
            printStats(lifecache, cache);
        }, 1, 1, TimeUnit.SECONDS);
        
        System.out.println("🌊 Demo running for 5 minutes... Press Ctrl+C to stop\n");
        
        // Run for 5 minutes
        Thread.sleep(5 * 60 * 1000);
        
        scheduler.shutdown();
        requestExecutor.shutdown();
        lifecache.close();
        
        System.out.println("\n🚢 Demo complete!");
        System.out.printf("📊 Total: %d requests, %d thundering herds%n", 
            totalRequests.get(), thunderingHerdCount.get());
    }
    
    private static void triggerThunderingHerd(Lifecache lifecache, LocalCache<String> cache, 
                                               ExecutorService executor) {
        int herdNumber = thunderingHerdCount.incrementAndGet();
        System.out.printf("%n⚡⚡⚡ THUNDERING HERD #%d - 10,000 requests incoming! ⚡⚡⚡%n", herdNumber);
        
        // Fire 10,000 requests as fast as possible
        for (int i = 0; i < 10000; i++) {
            final int requestId = totalRequests.incrementAndGet();
            executor.submit(() -> {
                try {
                    simulateRequest(lifecache, cache, requestId);
                } catch (Exception e) {
                    // Ignore
                }
            });
        }
    }
    
    private static void simulateRequest(Lifecache lifecache, LocalCache<String> cache, int requestId) {
        windowRequests.incrementAndGet();
        
        // Generate a key randomly from 100,000 possible keys
        String key = "item:" + random.nextInt(100000);
        
        // ============================================================
        // STEP 1: Check fallback FIRST (independent of cache!)
        // When latency is high, skip backend call entirely
        // ============================================================
        if (lifecache.shouldDrop("loadShedding")) {
            // High load - return fallback value, don't touch cache or backend
            windowFallbacks.incrementAndGet();
            // In real code: return FALLBACK_VALUE here
            return;
        }
        
        // ============================================================
        // STEP 2: Use cache normally (fallback already checked)
        // ============================================================
        LocalCache.CacheResult<String> result = cache.get(key, () -> {
            // This is the "backend call" - only executed on cache miss
            int concurrency = latencySimulator.incrementConcurrency();
            try {
                latencySimulator.sleep(concurrency);
                // Return a "computed" result
                return "result_" + key + "_" + System.currentTimeMillis();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "error";
            } finally {
                latencySimulator.decrementConcurrency();
            }
        });
        
        // Track cache results (window-based)
        if (result.isFromCache()) {
            windowHits.incrementAndGet();
        } else {
            windowMisses.incrementAndGet();
        }
    }
    
    private static void printStats(Lifecache lifecache, LocalCache<String> cache) {
        // Get window stats and reset
        int requests = windowRequests.getAndSet(0);
        int hits = windowHits.getAndSet(0);
        int misses = windowMisses.getAndSet(0);
        int fallbacks = windowFallbacks.getAndSet(0);
        
        // Get core output: healthScore (唯一核心输出)
        double health = lifecache.getHealthScore();
        Lifecache.Metrics metrics = lifecache.getMetrics();
        
        long uptime = (System.currentTimeMillis() - startTime.get()) / 1000;
        int concurrency = latencySimulator.getCurrentConcurrency();
        
        // Derived values from healthScore
        Duration softTtl = lifecache.getBreakdown("cacheTtl");
        double dropRate = lifecache.getBreakdown("loadShedding");
        
        int processed = hits + misses + fallbacks;
        double hitRate = processed > 0 ? 100.0 * hits / processed : 0;
        double fallbackRate = (misses + fallbacks) > 0 ? 100.0 * fallbacks / (misses + fallbacks) : 0;
        
        String statusEmoji = switch (metrics.getStatus()) {
            case "HEALTHY" -> "✅";
            case "DEGRADED" -> "⚠️";
            case "STRESSED" -> "🔶";
            case "CRITICAL" -> "🔴";
            default -> "❓";
        };
        
        System.out.println("\n📈 ========= Lifecache Stats (last 1s) ==========");
        System.out.printf("⏱️  Uptime: %ds | RPS: %d | Concurrency: %d | Herds: %d%n", 
            uptime, requests, concurrency, thunderingHerdCount.get());
        System.out.println("──────────────────────────────────────────────────");
        System.out.printf("📊 Requests: %d | Hits: %d | Misses: %d | Fallbacks: %d%n",
            requests, hits, misses, fallbacks);
        String cacheSize = cache.maxSize() > 0 
            ? String.format("%d/%d keys", cache.size(), cache.maxSize())
            : String.format("%d keys (unlimited)", cache.size());
        System.out.printf("📉 Hit Rate: %.1f%% | Fallback Rate: %.1f%% | Cache: %s%n", 
            hitRate, fallbackRate, cacheSize);
        System.out.println("──────────────────────────────────────────────────");
        System.out.printf("⚡ Latency P50/P95/P99: %.0f/%.0f/%.0fms%n",
            metrics.p50Latency(), metrics.p95Latency(), metrics.p99Latency());
        System.out.println("──────────────────────────────────────────────────");
        System.out.printf("🎯 Health Score: %.2f %s [%s]%n", health, statusEmoji, metrics.getStatus());
        System.out.printf("⏰ Soft TTL: %s | 🚫 Drop Rate: %.1f%%%n", formatDuration(softTtl), dropRate * 100);
        if (fallbacks > 0) {
            System.out.printf("⚠️  %d requests dropped (returned fallback)%n", fallbacks);
        }
        System.out.println("==================================================");
    }
    
    private static String formatDuration(Duration d) {
        long hours = d.toHours();
        long minutes = d.toMinutesPart();
        long seconds = d.toSecondsPart();
        
        if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        }
        return String.format("%ds", seconds);
    }
    
    /**
     * Generate RPS with normal distribution.
     * @param mean the mean value
     * @param stdDev standard deviation
     * @param min minimum value (clamped)
     * @param max maximum value (clamped)
     */
    private static int generateNormalRps(double mean, double stdDev, int min, int max) {
        double value = mean + random.nextGaussian() * stdDev;
        return (int) Math.max(min, Math.min(max, value));
    }
}
