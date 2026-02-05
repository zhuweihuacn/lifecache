package io.github.lifecache.demo;

import io.github.lifecache.config.QoSOutput;
import io.github.lifecache.config.QoSOutput.DecisionValue;
import io.github.lifecache.engine.ConfigurableQoSEngine;

import java.io.IOException;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 🚢 Lifecache Demo - Thundering Herd Simulation with JSON Config
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
 * <h2>Configuration</h2>
 * <p>All settings loaded from JSON config file: demo-config.json</p>
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
    
    // Simple in-memory cache
    private static final java.util.concurrent.ConcurrentHashMap<String, CacheEntry> cache = 
        new java.util.concurrent.ConcurrentHashMap<>();
    
    public static void main(String[] args) throws InterruptedException, IOException {
        System.out.println("🚢 ========================================");
        System.out.println("🚢 Lifecache - Thundering Herd Demo");
        System.out.println("🚢 ========================================");
        System.out.println("📄 Loading config from: demo-config.json");
        System.out.println("⚡ Simulating thundering herd every 20 seconds");
        System.out.println("📊 Background: ~100 RPS (normal distribution 0-500)");
        System.out.println("🌊 Thundering herd: 10,000 requests burst");
        System.out.println("⏱️  Demo runs for 5 minutes\n");
        
        // Load config from JSON
        ConfigurableQoSEngine engine = ConfigurableQoSEngine.fromResource("demo-config.json");
        
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
                        simulateRequest(engine, requestId);
                    } catch (Exception e) {
                        // Ignore
                    }
                });
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
        
        // Thundering herd - 10,000 requests every 20 seconds
        scheduler.scheduleAtFixedRate(() -> {
            triggerThunderingHerd(engine, requestExecutor);
        }, 25, 20, TimeUnit.SECONDS);
        
        // Stats printer - every 1 second
        scheduler.scheduleAtFixedRate(() -> {
            printStats(engine);
        }, 1, 1, TimeUnit.SECONDS);
        
        System.out.println("🌊 Demo running for 5 minutes... Press Ctrl+C to stop\n");
        
        // Run for 5 minutes
        Thread.sleep(5 * 60 * 1000);
        
        scheduler.shutdown();
        requestExecutor.shutdown();
        
        System.out.println("\n🚢 Demo complete!");
        System.out.printf("📊 Total: %d requests, %d thundering herds%n", 
            totalRequests.get(), thunderingHerdCount.get());
    }
    
    private static void triggerThunderingHerd(ConfigurableQoSEngine engine, ExecutorService executor) {
        int herdNumber = thunderingHerdCount.incrementAndGet();
        System.out.printf("%n⚡⚡⚡ THUNDERING HERD #%d - 10,000 requests incoming! ⚡⚡⚡%n", herdNumber);
        
        // Fire 10,000 requests as fast as possible
        for (int i = 0; i < 10000; i++) {
            final int requestId = totalRequests.incrementAndGet();
            executor.submit(() -> {
                try {
                    simulateRequest(engine, requestId);
                } catch (Exception e) {
                    // Ignore
                }
            });
        }
    }
    
    private static void simulateRequest(ConfigurableQoSEngine engine, int requestId) {
        windowRequests.incrementAndGet();
        
        // Generate a key randomly from 100,000 possible keys
        String key = "item:" + random.nextInt(100000);
        
        // Get current QoS decisions
        QoSOutput output = engine.evaluate();
        
        // ============================================================
        // STEP 1: Check load shedding (independent of cache!)
        // When latency is high, drop requests probabilistically
        // ============================================================
        DecisionValue loadSheddingDecision = output.decisions().get("loadShedding");
        double dropRate = loadSheddingDecision != null ? loadSheddingDecision.asDouble() : 0.0;
        
        if (dropRate > 0 && random.nextDouble() < dropRate) {
            // High load - return fallback value, don't touch cache or backend
            windowFallbacks.incrementAndGet();
            return;
        }
        
        // ============================================================
        // STEP 2: Use cache with adaptive TTL
        // ============================================================
        DecisionValue cacheTtlDecision = output.decisions().get("cacheTtl");
        Duration ttl = cacheTtlDecision != null ? cacheTtlDecision.asDuration() : Duration.ofSeconds(10);
        
        // Check cache
        CacheEntry entry = cache.get(key);
        if (entry != null && !entry.isExpired()) {
            windowHits.incrementAndGet();
            return;
        }
        
        // Cache miss - call backend
        windowMisses.incrementAndGet();
        
        int concurrency = latencySimulator.incrementConcurrency();
        try {
            long latency = latencySimulator.sleepAndGetLatency(concurrency);
            
            // Record latency to engine
            engine.record("latency", latency);
            
            // Store in cache with adaptive TTL
            cache.put(key, new CacheEntry("result_" + key, ttl));
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            latencySimulator.decrementConcurrency();
        }
    }
    
    private static void printStats(ConfigurableQoSEngine engine) {
        // Get window stats and reset
        int requests = windowRequests.getAndSet(0);
        int hits = windowHits.getAndSet(0);
        int misses = windowMisses.getAndSet(0);
        int fallbacks = windowFallbacks.getAndSet(0);
        
        // Get QoS output
        QoSOutput output = engine.evaluate();
        double health = output.healthScore();
        
        long uptime = (System.currentTimeMillis() - startTime.get()) / 1000;
        int concurrency = latencySimulator.getCurrentConcurrency();
        
        // Get decisions
        DecisionValue cacheTtlDecision = output.decisions().get("cacheTtl");
        DecisionValue loadSheddingDecision = output.decisions().get("loadShedding");
        Duration softTtl = cacheTtlDecision != null ? cacheTtlDecision.asDuration() : Duration.ofSeconds(10);
        double dropRate = loadSheddingDecision != null ? loadSheddingDecision.asDouble() : 0.0;
        
        int processed = hits + misses + fallbacks;
        double hitRate = processed > 0 ? 100.0 * hits / processed : 0;
        double fallbackRate = (misses + fallbacks) > 0 ? 100.0 * fallbacks / (misses + fallbacks) : 0;
        
        String status = getStatus(health);
        String statusEmoji = switch (status) {
            case "HEALTHY" -> "✅";
            case "DEGRADED" -> "⚠️";
            case "STRESSED" -> "🔶";
            case "CRITICAL" -> "🔴";
            default -> "❓";
        };
        
        // Get latency metrics
        Double p50 = output.metrics().get("latency_p95");
        Double p95 = output.metrics().get("latency_p95");
        Double p99 = output.metrics().get("latency_p95");
        
        System.out.println("\n📈 ========= Lifecache Stats (last 1s) ==========");
        System.out.printf("⏱️  Uptime: %ds | RPS: %d | Concurrency: %d | Herds: %d%n", 
            uptime, requests, concurrency, thunderingHerdCount.get());
        System.out.println("──────────────────────────────────────────────────");
        System.out.printf("📊 Requests: %d | Hits: %d | Misses: %d | Fallbacks: %d%n",
            requests, hits, misses, fallbacks);
        System.out.printf("📉 Hit Rate: %.1f%% | Fallback Rate: %.1f%% | Cache: %d keys%n", 
            hitRate, fallbackRate, cache.size());
        System.out.println("──────────────────────────────────────────────────");
        if (p95 != null) {
            System.out.printf("⚡ Latency P95: %.0fms%n", p95);
        }
        System.out.println("──────────────────────────────────────────────────");
        System.out.printf("🎯 Health Score: %.2f %s [%s]%n", health, statusEmoji, status);
        System.out.printf("⏰ Soft TTL: %s | 🚫 Drop Rate: %.1f%%%n", formatDuration(softTtl), dropRate * 100);
        if (fallbacks > 0) {
            System.out.printf("⚠️  %d requests dropped (returned fallback)%n", fallbacks);
        }
        System.out.println("==================================================");
    }
    
    private static String getStatus(double health) {
        if (health >= 0.9) return "HEALTHY";
        if (health >= 0.7) return "DEGRADED";
        if (health >= 0.4) return "STRESSED";
        return "CRITICAL";
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
     */
    private static int generateNormalRps(double mean, double stdDev, int min, int max) {
        double value = mean + random.nextGaussian() * stdDev;
        return (int) Math.max(min, Math.min(max, value));
    }
    
    /**
     * Simple cache entry with expiration.
     */
    private static class CacheEntry {
        private final String value;
        private final long expiresAt;
        
        CacheEntry(String value, Duration ttl) {
            this.value = value;
            this.expiresAt = System.currentTimeMillis() + ttl.toMillis();
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
}
