package io.github.lifecache.demo;

import io.github.lifecache.Lifecache;
import io.github.lifecache.metrics.MetricsCollector;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 🚢 Lifecache Demo - Quality-aware caching in action
 * 
 * <p>Uses {@link ConcurrencyLatencySimulator} to simulate realistic backend latency
 * that increases with concurrency, demonstrating how Lifecache adapts to load.</p>
 * 
 * <p>Inspired by <a href="https://github.com/zhuweihuacn/lifeboat">Lifeboat</a>'s latency simulation.</p>
 */
public class LifecacheDemo {
    
    private static final Random random = new Random();
    private static final AtomicInteger requestCount = new AtomicInteger(0);
    private static final AtomicInteger cacheHits = new AtomicInteger(0);
    private static final AtomicInteger cacheMisses = new AtomicInteger(0);
    private static final AtomicInteger droppedRequests = new AtomicInteger(0);
    private static final AtomicLong startTime = new AtomicLong();
    
    // Concurrency-based latency simulator
    private static final ConcurrencyLatencySimulator latencySimulator = new ConcurrencyLatencySimulator();
    
    // Variable load simulation
    private static volatile int targetRps = 10;
    
    public static void main(String[] args) throws InterruptedException {
        System.out.println("🚢 ========================================");
        System.out.println("🚢 Lifecache - Quality-Aware Caching Demo");
        System.out.println("🚢 ========================================");
        System.out.println("📊 Simulating concurrency-based latency");
        System.out.println("⏱️  Latency increases with concurrency level");
        System.out.println("🎯 Watch Lifecache adapt to changing conditions\n");
        
        Lifecache lifecache = Lifecache.builder()
            .defaultTtl(Duration.ofMinutes(10))
            .minTtl(Duration.ZERO)
            .maxTtl(Duration.ofMinutes(60))
            .latencyThreshold(50, 1.0)     // Concurrency < 5
            .latencyThreshold(100, 0.75)   // Concurrency 10-20
            .latencyThreshold(200, 0.5)    // Concurrency 20-30
            .latencyThreshold(400, 0.0)    // Concurrency 30+
            .fallbackThreshold(0.5)
            .dropStartThreshold(0.3)
            .maxDropRate(0.9)
            .metricsWindow(Duration.ofSeconds(5))
            .build();
        
        startTime.set(System.currentTimeMillis());
        
        // Thread pool for concurrent requests
        ExecutorService requestExecutor = Executors.newFixedThreadPool(50);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
        
        // Request generator - variable RPS
        scheduler.scheduleAtFixedRate(() -> {
            for (int i = 0; i < targetRps / 10; i++) {
                requestExecutor.submit(() -> {
                    try {
                        simulateRequest(lifecache);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
        
        // Stats printer - every 3 seconds
        scheduler.scheduleAtFixedRate(() -> {
            printStats(lifecache);
        }, 3, 3, TimeUnit.SECONDS);
        
        // Load variation - change RPS periodically
        scheduler.scheduleAtFixedRate(() -> {
            changeLoad();
        }, 8, 8, TimeUnit.SECONDS);
        
        System.out.println("🌊 Demo running... Press Ctrl+C to stop\n");
        
        Thread.sleep(90_000);
        
        scheduler.shutdown();
        requestExecutor.shutdown();
        lifecache.close();
        
        System.out.println("\n🚢 Demo complete!");
    }
    
    private static void simulateRequest(Lifecache lifecache) {
        requestCount.incrementAndGet();
        
        // Check if should drop (load shedding)
        if (lifecache.shouldDrop()) {
            droppedRequests.incrementAndGet();
            return;
        }
        
        // 70% cache hit rate
        boolean wouldHitCache = random.nextDouble() < 0.7;
        
        if (wouldHitCache) {
            cacheHits.incrementAndGet();
        } else {
            cacheMisses.incrementAndGet();
            
            // Track concurrency and simulate backend call
            int concurrency = latencySimulator.incrementConcurrency();
            try (MetricsCollector.Sample sample = lifecache.startSample()) {
                latencySimulator.sleep(concurrency);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latencySimulator.decrementConcurrency();
            }
        }
    }
    
    private static void changeLoad() {
        // Cycle through different load levels
        int[] loadLevels = {10, 20, 40, 80, 120, 80, 40, 20};
        int index = (int) ((System.currentTimeMillis() / 8000) % loadLevels.length);
        int newRps = loadLevels[index];
        
        if (newRps != targetRps) {
            targetRps = newRps;
            String emoji = getLoadEmoji(newRps);
            System.out.printf("%s Load changed: %d RPS (target concurrency ~%d)%n", 
                emoji, newRps, estimateConcurrency(newRps));
        }
    }
    
    private static String getLoadEmoji(int rps) {
        if (rps <= 20) return "✅";
        if (rps <= 50) return "⚠️";
        if (rps <= 100) return "🔶";
        return "🔴";
    }
    
    private static int estimateConcurrency(int rps) {
        // Rough estimate: concurrency ≈ RPS * avgLatency / 1000
        // At low load, avg ~50ms; at high load ~200ms
        return rps * 100 / 1000;
    }
    
    private static void printStats(Lifecache lifecache) {
        Lifecache.Decisions decisions = lifecache.getDecisions();
        Lifecache.Metrics metrics = lifecache.getMetrics();
        long uptime = (System.currentTimeMillis() - startTime.get()) / 1000;
        int concurrency = latencySimulator.getCurrentConcurrency();
        
        System.out.println("\n📈 ========== Lifecache Stats ==========");
        System.out.printf("⏱️  Uptime: %ds | RPS: %d | Concurrency: %d%n", uptime, targetRps, concurrency);
        System.out.println("─────────────────────────────────────────");
        System.out.printf("📊 Requests: %d | Hits: %d | Misses: %d | Dropped: %d%n",
            requestCount.get(), cacheHits.get(), cacheMisses.get(), droppedRequests.get());
        System.out.printf("📉 Hit Rate: %.1f%% | Drop Rate: %.1f%%%n",
            100.0 * cacheHits.get() / Math.max(1, requestCount.get() - droppedRequests.get()),
            100.0 * droppedRequests.get() / Math.max(1, requestCount.get()));
        System.out.println("─────────────────────────────────────────");
        System.out.printf("⚡ Latency P50/P95/P99: %.0f/%.0f/%.0fms%n",
            metrics.p50Latency(), metrics.p95Latency(), metrics.p99Latency());
        System.out.println("─────────────────────────────────────────");
        System.out.printf("🎯 Health Score: %.2f [%s]%n", decisions.healthScore(), decisions.getStatus());
        System.out.printf("⏰ Staleness: %s%n", formatDuration(decisions.staleness()));
        System.out.printf("🔄 Should Fallback: %s%n", decisions.shouldFallback() ? "YES ⚠️" : "NO");
        System.out.printf("🚫 Drop Rate: %.1f%%%n", decisions.dropRate() * 100);
        System.out.println("=========================================\n");
    }
    
    private static String formatDuration(Duration d) {
        long minutes = d.toMinutes();
        long seconds = d.toSecondsPart();
        if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        }
        return String.format("%ds", seconds);
    }
}
