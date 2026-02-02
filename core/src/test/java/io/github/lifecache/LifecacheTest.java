package io.github.lifecache;

import io.github.lifecache.cache.CacheEntry;
import io.github.lifecache.cache.LocalCache;
import io.github.lifecache.metrics.MetricType;
import io.github.lifecache.metrics.MetricsCollector;
import io.github.lifecache.metrics.SlidingWindowCollector;
import io.github.lifecache.policy.StepFunctionPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class LifecacheTest {
    
    @Test
    void testBuilderDefaults() {
        // No breakdowns by default
        Lifecache lifecache = Lifecache.builder().build();
        
        assertEquals(1.0, lifecache.getHealthScore());
        assertTrue(lifecache.getBreakdownNames().isEmpty());
    }
    
    @Test
    void testHealthySystem() {
        Lifecache lifecache = Lifecache.builder()
            .breakdown("ttl", Lifecache.linear(Duration.ofMinutes(10), Duration.ofMinutes(60)))
            .breakdown("drop", Lifecache.dropRate(0.3, 0.9))
            .build();
        
        // Low latency = healthy
        for (int i = 0; i < 10; i++) {
            lifecache.record(50);
        }
        
        assertTrue(lifecache.getHealthScore() >= 0.9);
        Duration ttl = lifecache.getBreakdown("ttl");
        assertTrue(ttl.toMinutes() <= 15);
        double dropRate = lifecache.getBreakdown("drop");
        assertEquals(0.0, dropRate);
    }
    
    @Test
    void testStressedSystem() {
        Lifecache lifecache = Lifecache.builder()
            .breakdown("ttl", Lifecache.linear(Duration.ofMinutes(10), Duration.ofMinutes(60)))
            .latencyThreshold(100, 1.0)
            .latencyThreshold(500, 0.0)
            .build();
        
        // High latency = stressed
        for (int i = 0; i < 10; i++) {
            lifecache.record(400);
        }
        
        assertTrue(lifecache.getHealthScore() < 0.5);
        Duration ttl = lifecache.getBreakdown("ttl");
        assertTrue(ttl.toMinutes() >= 30);
    }
    
    @Test
    void testCriticalSystem() {
        Lifecache lifecache = Lifecache.builder()
            .breakdown("ttl", Lifecache.linear(Duration.ofMinutes(10), Duration.ofMinutes(60)))
            .breakdown("drop", Lifecache.dropRate(0.3, 0.9))
            .latencyThreshold(100, 1.0)
            .latencyThreshold(500, 0.0)
            .build();
        
        // Very high latency = critical
        for (int i = 0; i < 10; i++) {
            lifecache.record(600);
        }
        
        assertTrue(lifecache.getHealthScore() < 0.1);
        Duration ttl = lifecache.getBreakdown("ttl");
        assertTrue(ttl.toMinutes() >= 50);
        double dropRate = lifecache.getBreakdown("drop");
        assertTrue(dropRate > 0);
    }
    
    @Test
    void testSampleAutoCloseable() throws InterruptedException {
        Lifecache lifecache = Lifecache.builder()
            .metricsWindow(Duration.ofSeconds(5))
            .build();
        
        try (MetricsCollector.Sample sample = lifecache.startSample()) {
            Thread.sleep(50);
        }
        
        assertTrue(lifecache.getP95Latency() >= 50);
    }
    
    @Test
    void testMetrics() {
        Lifecache lifecache = Lifecache.builder()
            .breakdown("ttl", Lifecache.linear(Duration.ofMinutes(1), Duration.ofMinutes(30)))
            .build();
        
        lifecache.record(200);
        lifecache.record(300);
        
        // Core output: healthScore
        double health = lifecache.getHealthScore();
        assertTrue(health >= 0 && health <= 1);
        
        // Breakdowns
        assertTrue(lifecache.hasBreakdown("ttl"));
        assertFalse(lifecache.hasBreakdown("nonexistent"));
        
        // Metrics snapshot (for debugging)
        Lifecache.Metrics metrics = lifecache.getMetrics();
        assertNotNull(metrics.getStatus());
        assertEquals(health, metrics.healthScore(), 0.01);
    }
    
    @Test
    void testStepFunctionBreakdown() throws InterruptedException {
        Lifecache lifecache = Lifecache.builder()
            .latencyThreshold(100, 1.0)
            .latencyThreshold(500, 0.0)
            .breakdown("ttl", Lifecache.stepFunction(
                Lifecache.entry(1.0, Duration.ofSeconds(10)),
                Lifecache.entry(0.5, Duration.ofMinutes(5)),
                Lifecache.entry(0.0, Duration.ofMinutes(30))
            ))
            .build();
        
        // Healthy - should return 10s
        Duration ttl = lifecache.getBreakdown("ttl");
        assertEquals(Duration.ofSeconds(10), ttl);
        
        // Add high latency samples
        for (int i = 0; i < 10; i++) {
            lifecache.record(300);  // Should result in ~0.5 health
        }
        
        // Wait for metrics cache to refresh
        Thread.sleep(150);
        
        // Health around 0.5, should be around 5 minutes
        Duration staleness = lifecache.getBreakdown("ttl");
        long seconds = staleness.toSeconds();
        assertTrue(seconds >= 60 && seconds <= 600, 
            "Expected 1-10 minutes but got " + seconds + "s (health=" + lifecache.getHealthScore() + ")");
    }
    
    @Test
    void testSigmoidBreakdown() throws InterruptedException {
        Lifecache lifecache = Lifecache.builder()
            .breakdown("ttl", Lifecache.sigmoid(Duration.ofSeconds(10), Duration.ofMinutes(30), 5.0))
            .build();
        
        // Healthy - should be close to min (but sigmoid curve means not exactly min)
        Duration healthyTtl = lifecache.getBreakdown("ttl");
        assertTrue(healthyTtl.toMinutes() < 20,
            "Expected < 20 minutes but got " + healthyTtl);
        
        // Simulate low health
        for (int i = 0; i < 10; i++) {
            lifecache.record(600);
        }
        
        // Wait for metrics cache to refresh
        Thread.sleep(150);
        
        Duration stressedTtl = lifecache.getBreakdown("ttl");
        assertTrue(stressedTtl.toMinutes() > 10,
            "Expected > 10 minutes but got " + stressedTtl + " (health=" + lifecache.getHealthScore() + ")");
    }
    
    @Test
    void testCustomBreakdown() {
        Lifecache lifecache = Lifecache.builder()
            .breakdown("ttl", health -> health > 0.5 
                ? Duration.ofSeconds(30) 
                : Duration.ofMinutes(10))
            .breakdown("priority", health -> health > 0.8 ? 1 : health > 0.5 ? 2 : 3)
            .build();
        
        // Healthy - 30 seconds
        Duration ttl = lifecache.getBreakdown("ttl");
        assertEquals(Duration.ofSeconds(30), ttl);
        
        // Healthy - priority 1
        int priority = lifecache.getBreakdown("priority");
        assertEquals(1, priority);
    }
    
    @Test
    void testShouldDrop() throws InterruptedException {
        Lifecache lifecache = Lifecache.builder()
            .breakdown("loadShedding", Lifecache.dropRate(0.5, 1.0))
            .latencyThreshold(100, 1.0)
            .latencyThreshold(500, 0.0)
            .build();
        
        // Healthy - should not drop
        assertFalse(lifecache.shouldDrop("loadShedding"));
        
        // Simulate critical system
        for (int i = 0; i < 10; i++) {
            lifecache.record(600);
        }
        
        // Wait for metrics cache to refresh
        Thread.sleep(150);
        
        // Now health < 0.5, dropRate should be high
        double dropRate = lifecache.getBreakdown("loadShedding");
        assertTrue(dropRate > 0.5, "Expected dropRate > 0.5 but got " + dropRate + " (health=" + lifecache.getHealthScore() + ")");
    }
}

class SlidingWindowCollectorTest {
    
    @Test
    void testPercentiles() {
        SlidingWindowCollector collector = new SlidingWindowCollector(Duration.ofSeconds(10));
        
        for (int i = 1; i <= 10; i++) {
            collector.record(i * 10);
        }
        
        double p50 = collector.getP50();
        assertTrue(p50 >= 50 && p50 <= 60);
        
        double p95 = collector.getP95();
        assertTrue(p95 >= 90 && p95 <= 100);
    }
    
    @Test
    void testEmptyCollector() {
        SlidingWindowCollector collector = new SlidingWindowCollector();
        
        assertEquals(0, collector.getP50());
        assertEquals(0, collector.getP95());
        assertEquals(0, collector.getSampleCount());
    }
    
    @Test
    void testStartSample() throws InterruptedException {
        SlidingWindowCollector collector = new SlidingWindowCollector();
        
        try (MetricsCollector.Sample sample = collector.startSample()) {
            Thread.sleep(10);
        }
        
        assertTrue(collector.getSampleCount() > 0);
        assertTrue(collector.getP95() >= 10);
    }
    
    @Test
    void testClear() {
        SlidingWindowCollector collector = new SlidingWindowCollector();
        
        collector.record(100);
        collector.record(200);
        assertEquals(2, collector.getSampleCount());
        
        collector.clear();
        assertEquals(0, collector.getSampleCount());
    }
    
    @Test
    void testRead() {
        SlidingWindowCollector collector = new SlidingWindowCollector();
        
        collector.record(100);
        collector.record(200);
        collector.record(300);
        
        // Test read() with different MetricTypes
        assertEquals(3, collector.read(MetricType.SAMPLE_COUNT).intValue());
        assertTrue(collector.read(MetricType.AVERAGE) > 0);
        assertEquals(200, collector.read(MetricType.P50), 1.0); // median
    }
    
    @Test
    void testReadReturnsNullWhenEmpty() {
        SlidingWindowCollector collector = new SlidingWindowCollector();
        
        // No samples - should return null
        assertNull(collector.read(MetricType.P95));
        assertNull(collector.read(MetricType.AVERAGE));
    }
}

class PolicyTest {
    
    @Test
    void testStepFunctionPolicy() {
        StepFunctionPolicy policy = new StepFunctionPolicy();
        SlidingWindowCollector collector = new SlidingWindowCollector();
        
        assertEquals(1.0, policy.evaluate(collector));
        
        collector.record(50);
        assertEquals(1.0, policy.evaluate(collector));
    }
    
    @Test
    void testStepFunctionPolicyDegraded() {
        StepFunctionPolicy policy = new StepFunctionPolicy();
        SlidingWindowCollector collector = new SlidingWindowCollector();
        
        collector.record(300);
        double health = policy.evaluate(collector);
        assertTrue(health >= 0.45 && health <= 0.55);
    }
    
    @Test
    void testStepFunctionPolicyCritical() {
        StepFunctionPolicy policy = new StepFunctionPolicy();
        SlidingWindowCollector collector = new SlidingWindowCollector();
        
        collector.record(600);
        double health = policy.evaluate(collector);
        assertEquals(0.0, health);
    }
}

class LocalCacheTest {
    
    @Test
    void testCacheHitAndMiss() {
        Lifecache lifecache = Lifecache.builder()
            .breakdown("cacheTtl", Lifecache.linear(Duration.ofMinutes(10), Duration.ofHours(1)))
            .build();
        
        LocalCache<String> cache = new LocalCache<>(lifecache, "cacheTtl");
        
        int[] computeCount = {0};
        
        // First call - cache miss
        LocalCache.CacheResult<String> result1 = cache.get("key1", () -> {
            computeCount[0]++;
            return "value1";
        });
        
        assertFalse(result1.isFromCache());
        assertEquals("value1", result1.value());
        assertEquals(1, computeCount[0]);
        
        // Second call - cache hit
        LocalCache.CacheResult<String> result2 = cache.get("key1", () -> {
            computeCount[0]++;
            return "value1-new";
        });
        
        assertTrue(result2.isFromCache());
        assertEquals("value1", result2.value()); // Original value
        assertEquals(1, computeCount[0]); // Loader not called
    }
    
    @Test
    void testCacheEntry() throws InterruptedException {
        CacheEntry<String> entry = CacheEntry.of("test");
        
        assertEquals("test", entry.value());
        assertTrue(entry.timestampMs() > 0);
        assertTrue(entry.ageMs() >= 0);
        
        // Should not be expired with long TTL
        assertFalse(entry.isExpired(60_000));
        
        // Wait a bit then check expired with short TTL
        Thread.sleep(10);
        assertTrue(entry.isExpired(5)); // 5ms TTL, entry is ~10ms old
    }
    
    @Test
    void testCacheOperations() {
        Lifecache lifecache = Lifecache.builder()
            .breakdown("ttl", Lifecache.linear(Duration.ofMinutes(1), Duration.ofMinutes(30)))
            .build();
        LocalCache<String> cache = new LocalCache<>(lifecache, "ttl");
        
        // Put and peek
        cache.put("key1", "value1");
        CacheEntry<String> entry = cache.peek("key1");
        assertNotNull(entry);
        assertEquals("value1", entry.value());
        
        // Size
        assertEquals(1, cache.size());
        
        // Invalidate
        cache.invalidate("key1");
        assertNull(cache.peek("key1"));
        assertEquals(0, cache.size());
        
        // Clear
        cache.put("key2", "value2");
        cache.put("key3", "value3");
        assertEquals(2, cache.size());
        cache.clear();
        assertEquals(0, cache.size());
    }
    
    @Test
    void testAdaptiveTtl() throws InterruptedException {
        Lifecache lifecache = Lifecache.builder()
            .breakdown("ttl", Lifecache.linear(Duration.ofMinutes(1), Duration.ofHours(1)))
            .latencyThreshold(100, 1.0)
            .latencyThreshold(500, 0.0)
            .build();
        
        LocalCache<String> cache = new LocalCache<>(lifecache, "ttl");
        
        // When healthy (no samples), soft TTL should be close to min (1min)
        Duration healthyTtl = cache.getCurrentSoftTtl();
        assertEquals(Duration.ofMinutes(1), healthyTtl);
        
        // Simulate high latency (at threshold where health = 0)
        for (int i = 0; i < 10; i++) {
            lifecache.record(500);
        }
        
        // Wait for metrics cache to refresh (100ms cache validity)
        Thread.sleep(150);
        
        // Now soft TTL should be maxTtl (1 hour) since health = 0
        Duration stressedTtl = cache.getCurrentSoftTtl();
        assertEquals(Duration.ofHours(1), stressedTtl);
    }
}
