package io.github.lifecache;

import io.github.lifecache.cache.CacheEntry;
import io.github.lifecache.cache.LocalCache;
import io.github.lifecache.decision.*;
import io.github.lifecache.metrics.Aggregation;
import io.github.lifecache.metrics.MetricSample;
import io.github.lifecache.metrics.MetricsRegistry;
import io.github.lifecache.metrics.Aggregation;
import io.github.lifecache.metrics.SlidingWindowRegistry;
import io.github.lifecache.policy.StepFunctionEvaluator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LifecacheTest {
    
    private static final Duration WINDOW = Duration.ofSeconds(10);
    
    @Test
    void testHealthySystem() {
        Lifecache lifecache = Lifecache.builder()
            .metricName("latency")
            .aggregation(Aggregation.P95)
            .metricsWindow(WINDOW)
            .threshold(100, 1.0)
            .threshold(500, 0.0)
            .breakdown("ttl", Lifecache.linear(Duration.ofMinutes(10), Duration.ofMinutes(60)))
            .breakdown("drop", Lifecache.dropRate(0.3, 0.9))
            .build();
        
        // Low latency = healthy
        for (int i = 0; i < 10; i++) {
            lifecache.record(MetricSample.of("latency", 50));
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
            .metricName("latency")
            .aggregation(Aggregation.P95)
            .metricsWindow(WINDOW)
            .threshold(100, 1.0)
            .threshold(500, 0.0)
            .breakdown("ttl", Lifecache.linear(Duration.ofMinutes(10), Duration.ofMinutes(60)))
            .build();
        
        // High latency = stressed
        for (int i = 0; i < 10; i++) {
            lifecache.record(MetricSample.of("latency", 400));
        }
        
        assertTrue(lifecache.getHealthScore() < 0.5);
        Duration ttl = lifecache.getBreakdown("ttl");
        assertTrue(ttl.toMinutes() >= 30);
    }
    
    @Test
    void testCriticalSystem() {
        Lifecache lifecache = Lifecache.builder()
            .metricName("latency")
            .aggregation(Aggregation.P95)
            .metricsWindow(WINDOW)
            .threshold(100, 1.0)
            .threshold(500, 0.0)
            .breakdown("ttl", Lifecache.linear(Duration.ofMinutes(10), Duration.ofMinutes(60)))
            .breakdown("drop", Lifecache.dropRate(0.3, 0.9))
            .build();
        
        // Very high latency = critical
        for (int i = 0; i < 10; i++) {
            lifecache.record(MetricSample.of("latency", 600));
        }
        
        assertTrue(lifecache.getHealthScore() < 0.1);
        Duration ttl = lifecache.getBreakdown("ttl");
        assertTrue(ttl.toMinutes() >= 50);
        double dropRate = lifecache.getBreakdown("drop");
        assertTrue(dropRate > 0);
    }
    
    @Test
    void testMetrics() {
        Lifecache lifecache = Lifecache.builder()
            .metricName("latency")
            .aggregation(Aggregation.P95)
            .metricsWindow(WINDOW)
            .threshold(100, 1.0)
            .threshold(500, 0.0)
            .breakdown("ttl", Lifecache.linear(Duration.ofMinutes(1), Duration.ofMinutes(30)))
            .build();
        
        lifecache.record(MetricSample.of("latency", 200));
        lifecache.record(MetricSample.of("latency", 300));
        
        // Core output: healthScore
        double health = lifecache.getHealthScore();
        assertTrue(health >= 0 && health <= 1);
        
        // Breakdowns
        assertTrue(lifecache.hasBreakdown("ttl"));
        assertFalse(lifecache.hasBreakdown("nonexistent"));
    }
    
    @Test
    void testStepFunctionBreakdown() throws InterruptedException {
        Lifecache lifecache = Lifecache.builder()
            .metricName("latency")
            .aggregation(Aggregation.P95)
            .metricsWindow(WINDOW)
            .threshold(100, 1.0)
            .threshold(500, 0.0)
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
            lifecache.record(MetricSample.of("latency", 300));  // Should result in ~0.5 health
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
            .metricName("latency")
            .aggregation(Aggregation.P95)
            .metricsWindow(WINDOW)
            .threshold(100, 1.0)
            .threshold(500, 0.0)
            .breakdown("ttl", Lifecache.sigmoid(Duration.ofSeconds(10), Duration.ofMinutes(30), 5.0))
            .build();
        
        // Healthy - should be close to min (but sigmoid curve means not exactly min)
        Duration healthyTtl = lifecache.getBreakdown("ttl");
        assertTrue(healthyTtl.toMinutes() < 20,
            "Expected < 20 minutes but got " + healthyTtl);
        
        // Simulate low health
        for (int i = 0; i < 10; i++) {
            lifecache.record(MetricSample.of("latency", 600));
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
            .metricName("latency")
            .aggregation(Aggregation.P95)
            .metricsWindow(WINDOW)
            .threshold(100, 1.0)
            .threshold(500, 0.0)
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
            .metricName("latency")
            .aggregation(Aggregation.P95)
            .metricsWindow(WINDOW)
            .threshold(100, 1.0)
            .threshold(500, 0.0)
            .breakdown("loadShedding", Lifecache.dropRate(0.5, 1.0))
            .build();
        
        // Healthy - should not drop
        assertFalse(lifecache.shouldDrop("loadShedding"));
        
        // Simulate critical system
        for (int i = 0; i < 10; i++) {
            lifecache.record(MetricSample.of("latency", 600));
        }
        
        // Wait for metrics cache to refresh
        Thread.sleep(150);
        
        // Now health < 0.5, dropRate should be high
        double dropRate = lifecache.getBreakdown("loadShedding");
        assertTrue(dropRate > 0.5, "Expected dropRate > 0.5 but got " + dropRate + " (health=" + lifecache.getHealthScore() + ")");
    }
}

class SlidingWindowRegistryTest {
    
    private static final Duration WINDOW = Duration.ofSeconds(10);
    
    @Test
    void testAggregations() {
        SlidingWindowRegistry registry = new SlidingWindowRegistry(WINDOW);
        
        for (int i = 1; i <= 10; i++) {
            registry.record(MetricSample.of("latency", i * 10));
        }
        
        Double p50 = registry.read("latency", Aggregation.P50, WINDOW);
        assertNotNull(p50);
        assertTrue(p50 >= 50 && p50 <= 60);
        
        Double p95 = registry.read("latency", Aggregation.P95, WINDOW);
        assertNotNull(p95);
        assertTrue(p95 >= 90 && p95 <= 100);
    }
    
    @Test
    void testEmptyRegistry() {
        SlidingWindowRegistry registry = new SlidingWindowRegistry();
        
        assertNull(registry.read("latency", Aggregation.P50, WINDOW));
        assertNull(registry.read("latency", Aggregation.P95, WINDOW));
        assertEquals(0, registry.getSampleCount());
    }
    
    @Test
    void testClear() {
        SlidingWindowRegistry registry = new SlidingWindowRegistry();
        
        registry.record(MetricSample.of("latency", 100));
        registry.record(MetricSample.of("latency", 200));
        assertEquals(2, registry.getSampleCount("latency", WINDOW));
        
        registry.clear();
        assertEquals(0, registry.getSampleCount("latency", WINDOW));
    }
    
    @Test
    void testRead() {
        SlidingWindowRegistry registry = new SlidingWindowRegistry();
        
        registry.record(MetricSample.of("latency", 100));
        registry.record(MetricSample.of("latency", 200));
        registry.record(MetricSample.of("latency", 300));
        
        // Test read() with explicit aggregation
        assertTrue(registry.read("latency", Aggregation.AVG, WINDOW) > 0);
        assertEquals(200, registry.read("latency", Aggregation.P50, WINDOW), 1.0); // median
    }
    
    @Test
    void testReadReturnsNullWhenEmpty() {
        SlidingWindowRegistry registry = new SlidingWindowRegistry();
        
        // No samples - should return null
        assertNull(registry.read("latency", Aggregation.P95, WINDOW));
        assertNull(registry.read("latency", Aggregation.AVG, WINDOW));
    }
}

class QoSEvaluatorTest {
    
    private static final Duration WINDOW = Duration.ofSeconds(10);
    
    @Test
    void testStepFunctionEvaluator() {
        SlidingWindowRegistry registry = new SlidingWindowRegistry();
        
        StepFunctionEvaluator evaluator = StepFunctionEvaluator.builder()
            .metricsReader(registry)
            .metricName("latency")
            .aggregation(Aggregation.P95)
            .window(WINDOW)
            .threshold(100, 1.0)
            .threshold(200, 0.75)
            .threshold(300, 0.5)
            .threshold(400, 0.25)
            .threshold(500, 0.0)
            .build();
        
        assertEquals(1.0, evaluator.evaluate());
        
        registry.record(MetricSample.of("latency", 50));
        assertEquals(1.0, evaluator.evaluate());
    }
    
    @Test
    void testStepFunctionEvaluatorDegraded() {
        SlidingWindowRegistry registry = new SlidingWindowRegistry();
        
        StepFunctionEvaluator evaluator = StepFunctionEvaluator.builder()
            .metricsReader(registry)
            .metricName("latency")
            .aggregation(Aggregation.P95)
            .window(WINDOW)
            .threshold(100, 1.0)
            .threshold(200, 0.75)
            .threshold(300, 0.5)
            .threshold(400, 0.25)
            .threshold(500, 0.0)
            .build();
        
        registry.record(MetricSample.of("latency", 300));
        double health = evaluator.evaluate();
        assertTrue(health >= 0.45 && health <= 0.55);
    }
    
    @Test
    void testStepFunctionEvaluatorCritical() {
        SlidingWindowRegistry registry = new SlidingWindowRegistry();
        
        StepFunctionEvaluator evaluator = StepFunctionEvaluator.builder()
            .metricsReader(registry)
            .metricName("latency")
            .aggregation(Aggregation.P95)
            .window(WINDOW)
            .threshold(100, 1.0)
            .threshold(500, 0.0)
            .build();
        
        registry.record(MetricSample.of("latency", 600));
        double health = evaluator.evaluate();
        assertEquals(0.0, health);
    }
    
    @Test
    void testCustomThresholds() {
        SlidingWindowRegistry registry = new SlidingWindowRegistry();
        
        StepFunctionEvaluator evaluator = StepFunctionEvaluator.builder()
            .metricsReader(registry)
            .metricName("latency")
            .aggregation(Aggregation.P95)
            .window(WINDOW)
            .threshold(50, 1.0)
            .threshold(100, 0.5)
            .threshold(200, 0.0)
            .build();
        
        registry.record(MetricSample.of("latency", 75));  // Midpoint between 50 and 100
        double health = evaluator.evaluate();
        assertTrue(health >= 0.7 && health <= 0.8);
    }
}

class LocalCacheTest {
    
    private static final Duration WINDOW = Duration.ofSeconds(10);
    
    @Test
    void testCacheHitAndMiss() {
        Lifecache lifecache = Lifecache.builder()
            .metricName("latency")
            .aggregation(Aggregation.P95)
            .metricsWindow(WINDOW)
            .threshold(100, 1.0)
            .threshold(500, 0.0)
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
            .metricName("latency")
            .aggregation(Aggregation.P95)
            .metricsWindow(WINDOW)
            .threshold(100, 1.0)
            .threshold(500, 0.0)
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
            .metricName("latency")
            .aggregation(Aggregation.P95)
            .metricsWindow(WINDOW)
            .threshold(100, 1.0)
            .threshold(500, 0.0)
            .breakdown("ttl", Lifecache.linear(Duration.ofMinutes(1), Duration.ofHours(1)))
            .build();
        
        LocalCache<String> cache = new LocalCache<>(lifecache, "ttl");
        
        // When healthy (no samples), soft TTL should be close to min (1min)
        Duration healthyTtl = cache.getCurrentSoftTtl();
        assertEquals(Duration.ofMinutes(1), healthyTtl);
        
        // Simulate high latency (at threshold where health = 0)
        for (int i = 0; i < 10; i++) {
            lifecache.record(MetricSample.of("latency", 500));
        }
        
        // Wait for metrics cache to refresh (100ms cache validity)
        Thread.sleep(150);
        
        // Now soft TTL should be maxTtl (1 hour) since health = 0
        Duration stressedTtl = cache.getCurrentSoftTtl();
        assertEquals(Duration.ofHours(1), stressedTtl);
    }
}

/**
 * Tests for Decision API (DecisionRule, QoSDecision).
 */
class DecisionApiTest {
    
    private static final Duration WINDOW = Duration.ofSeconds(10);
    
    @Test
    void testDecisionRuleThrottle() {
        // Test linear rate rule (replaces ThrottleDecision)
        DecisionRule<Double> rule = DecisionRule.linearRate(0.5, 0.9);
        
        // Health >= threshold → no throttle
        assertEquals(0.0, rule.apply(1.0));
        assertEquals(0.0, rule.apply(0.5));
        
        // Health < threshold → throttle increases
        assertTrue(rule.apply(0.4) > 0);
        assertTrue(rule.apply(0.25) > rule.apply(0.4));
        
        // Health = 0 → max throttle
        assertEquals(0.9, rule.apply(0.0), 0.01);
    }
    
    @Test
    void testDecisionRuleDuration() {
        // Test linear duration rule (replaces SoftTTLDecision)
        DecisionRule<Duration> rule = DecisionRule.linearDuration(
            Duration.ofSeconds(30), Duration.ofMinutes(30));
        
        // Healthy → min TTL
        assertEquals(Duration.ofSeconds(30), rule.apply(1.0));
        
        // Critical → max TTL
        assertEquals(Duration.ofMinutes(30), rule.apply(0.0));
        
        // Middle → interpolated
        Duration midTTL = rule.apply(0.5);
        assertTrue(midTTL.toSeconds() > 30);
        assertTrue(midTTL.toMinutes() < 30);
    }
    
    @Test
    void testQoSDecisionFromRule() {
        // Define individual rules using step function
        DecisionRule<Duration> ttlRule = DecisionRule.stepsWithInterpolation(
            DecisionRule.step(1.0, Duration.ofSeconds(30)),
            DecisionRule.step(0.0, Duration.ofMinutes(30))
        );
        DecisionRule<Double> throttleRule = DecisionRule.stepsWithDoubleInterpolation(
            DecisionRule.step(0.5, 0.0),
            DecisionRule.step(0.0, 0.9)
        );
        DecisionRule<Integer> priorityRule = DecisionRule.steps(
            DecisionRule.step(0.8, 1),
            DecisionRule.step(0.5, 2),
            DecisionRule.step(0.0, 3)
        );
        
        // Each rule generates one typed decision from health = 0.75
        double health = 0.75;
        QoSDecision<Duration> ttlDecision = ttlRule.evaluate(health);
        QoSDecision<Double> throttleDecision = throttleRule.evaluate(health);
        QoSDecision<Integer> priorityDecision = priorityRule.evaluate(health);
        
        assertNotNull(ttlDecision.value());
        assertEquals(0.0, throttleDecision.value());  // Above 0.5 threshold
        assertEquals(2, priorityDecision.value());   // Between 0.5 and 0.8
    }
    
    @Test
    void testQoSDecisionTypedAccessors() {
        // Test various typed accessors
        double health = 0.35;
        
        DecisionRule<Double> throttleRule = DecisionRule.stepsWithDoubleInterpolation(
            DecisionRule.step(0.5, 0.0),
            DecisionRule.step(0.0, 0.9)
        );
        DecisionRule<Duration> ttlRule = DecisionRule.stepsWithInterpolation(
            DecisionRule.step(1.0, Duration.ofSeconds(30)),
            DecisionRule.step(0.0, Duration.ofMinutes(30))
        );
        DecisionRule<Integer> priorityRule = DecisionRule.steps(
            DecisionRule.step(0.8, 1),
            DecisionRule.step(0.5, 2),
            DecisionRule.step(0.0, 3)
        );
        DecisionRule<Boolean> circuitRule = DecisionRule.steps(
            DecisionRule.step(0.3, false),
            DecisionRule.step(0.0, true)
        );
        
        QoSDecision<Double> throttleDecision = throttleRule.evaluate(health);
        QoSDecision<Duration> ttlDecision = ttlRule.evaluate(health);
        QoSDecision<Integer> priorityDecision = priorityRule.evaluate(health);
        QoSDecision<Boolean> circuitDecision = circuitRule.evaluate(health);
        
        // Type-safe value access
        assertTrue(throttleDecision.value() > 0);  // Below 0.5 threshold, throttling kicks in
        assertNotNull(ttlDecision.value());
        assertEquals(3, priorityDecision.value());  // Below 0.5 → priority 3
        assertFalse(circuitDecision.value());  // Above 0.3 → circuit not open
    }
    
    @Test
    void testQoSDecisionOf() {
        QoSDecision<Duration> decision = QoSDecision.of(Duration.ofSeconds(30));
        assertEquals(Duration.ofSeconds(30), decision.value());
    }
    
    @Test
    void testDecisionRuleStepWithInterpolation() {
        // Step function with interpolation for rate
        DecisionRule<Double> rule = DecisionRule.stepsWithDoubleInterpolation(
            DecisionRule.step(0.5, 0.0),
            DecisionRule.step(0.0, 0.9)
        );
        
        // At threshold - no throttle
        assertEquals(0.0, rule.apply(1.0), 0.01);
        assertEquals(0.0, rule.apply(0.5), 0.01);
        
        // Below threshold - throttle increases (interpolated)
        double degraded = rule.apply(0.25);
        assertTrue(degraded > 0);
        assertTrue(degraded < 0.9);
        
        // Critical - max throttle
        assertEquals(0.9, rule.apply(0.0), 0.01);
    }
    
    @Test
    void testDecisionRuleStepDuration() {
        // Step function with interpolation for duration
        DecisionRule<Duration> rule = DecisionRule.stepsWithInterpolation(
            DecisionRule.step(1.0, Duration.ofSeconds(10)),
            DecisionRule.step(0.0, Duration.ofMinutes(10))
        );
        
        // Healthy - min TTL
        assertEquals(Duration.ofSeconds(10), rule.apply(1.0));
        
        // Critical - max TTL
        assertEquals(Duration.ofMinutes(10), rule.apply(0.0));
        
        // Midpoint (interpolated)
        Duration midTTL = rule.apply(0.5);
        assertTrue(midTTL.toSeconds() > 10);
        assertTrue(midTTL.toMinutes() < 10);
    }
    
    @Test
    void testDecisionRuleSteps() {
        // Pure step function (no interpolation)
        DecisionRule<Integer> rule = DecisionRule.steps(
            DecisionRule.step(0.8, 1),
            DecisionRule.step(0.5, 2),
            DecisionRule.step(0.0, 3)
        );
        
        assertEquals(1, rule.apply(1.0));
        assertEquals(1, rule.apply(0.8));
        assertEquals(2, rule.apply(0.7));
        assertEquals(2, rule.apply(0.5));
        assertEquals(3, rule.apply(0.4));
        assertEquals(3, rule.apply(0.0));
    }
    
    @Test
    void testDecisionRuleStepDurationMultiple() {
        // Step function with multiple thresholds and interpolation
        DecisionRule<Duration> rule = DecisionRule.stepsWithInterpolation(
            DecisionRule.step(1.0, Duration.ofSeconds(10)),
            DecisionRule.step(0.5, Duration.ofMinutes(5)),
            DecisionRule.step(0.0, Duration.ofMinutes(30))
        );
        
        // At threshold points
        assertEquals(Duration.ofSeconds(10), rule.apply(1.0));
        assertEquals(Duration.ofMinutes(5), rule.apply(0.5));
        assertEquals(Duration.ofMinutes(30), rule.apply(0.0));
    }
    
    @Test
    void testLifecacheWithExplicitDecisions() throws InterruptedException {
        Lifecache qos = Lifecache.builder()
            .metricName("latency")
            .aggregation(Aggregation.P95)
            .metricsWindow(WINDOW)
            .threshold(100, 1.0)
            .threshold(500, 0.0)
            .rule("throttleRate", DecisionRule.stepsWithDoubleInterpolation(
                DecisionRule.step(0.5, 0.0),
                DecisionRule.step(0.0, 0.9)
            ))
            .rule("allowStaleness", DecisionRule.stepsWithInterpolation(
                DecisionRule.step(1.0, Duration.ofSeconds(30)),
                DecisionRule.step(0.0, Duration.ofMinutes(30))
            ))
            .build();
        
        // Healthy state - each rule returns one typed decision
        QoSDecision<Double> throttleDecision = qos.getDecision("throttleRate");
        QoSDecision<Duration> ttlDecision = qos.getDecision("allowStaleness");
        
        assertEquals(1.0, qos.getHealthScore());
        assertEquals(0.0, throttleDecision.value());
        assertEquals(Duration.ofSeconds(30), ttlDecision.value());
        
        // Convenience methods
        assertFalse(qos.shouldThrottle());
        assertEquals(Duration.ofSeconds(30), qos.getAllowStaleness());
        
        // Simulate degradation
        for (int i = 0; i < 10; i++) {
            qos.record(MetricSample.of("latency", 500));
        }
        Thread.sleep(150);
        
        // Degraded state - each rule returns one typed decision
        assertTrue(qos.getHealthScore() < 0.1);
        QoSDecision<Double> degradedThrottle = qos.getDecision("throttleRate");
        QoSDecision<Duration> degradedTtl = qos.getDecision("allowStaleness");
        
        assertTrue(degradedThrottle.value() > 0.8);
        assertTrue(degradedTtl.value().toMinutes() > 25);
    }
    
    @Test
    void testLifecacheWithCustomRules() {
        Lifecache qos = Lifecache.builder()
            .metricName("latency")
            .aggregation(Aggregation.P95)
            .metricsWindow(WINDOW)
            .threshold(100, 1.0)
            .threshold(500, 0.0)
            .rule("throttleRate", DecisionRule.stepsWithDoubleInterpolation(
                DecisionRule.step(0.7, 0.0),
                DecisionRule.step(0.0, 0.8)
            ))
            .rule("allowStaleness", DecisionRule.stepsWithInterpolation(
                DecisionRule.step(1.0, Duration.ofSeconds(10)),
                DecisionRule.step(0.7, Duration.ofSeconds(30)),
                DecisionRule.step(0.5, Duration.ofMinutes(3)),
                DecisionRule.step(0.3, Duration.ofMinutes(6)),
                DecisionRule.step(0.0, Duration.ofMinutes(10))
            ))
            .build();
        
        // Get decisions - each rule returns one typed decision
        QoSDecision<Double> throttleDecision = qos.getDecision("throttleRate");
        QoSDecision<Duration> ttlDecision = qos.getDecision("allowStaleness");
        
        // Healthy
        assertEquals(0.0, throttleDecision.value());  // Above 0.7 threshold
        assertTrue(ttlDecision.value().toSeconds() < 60);
    }
}
