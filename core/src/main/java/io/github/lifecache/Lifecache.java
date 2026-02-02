package io.github.lifecache;

import io.github.lifecache.metrics.MetricsCollector;
import io.github.lifecache.metrics.SlidingWindowCollector;
import io.github.lifecache.policy.HealthPolicy;
import io.github.lifecache.policy.StepFunctionPolicy;

import java.time.Duration;
import java.util.*;
import java.util.function.Function;

/**
 * 🚢 Lifecache - Quality-aware caching that keeps your system afloat
 * 
 * <h2>Quick Start</h2>
 * <pre>{@code
 * Lifecache lifecache = Lifecache.builder()
 *     .latencyThreshold(100, 1.0)   // P95 < 100ms = healthy
 *     .latencyThreshold(300, 0.5)   // P95 = 300ms = degraded
 *     .latencyThreshold(500, 0.0)   // P95 > 500ms = critical
 *     .staleness(Lifecache.linear(
 *         Duration.ofSeconds(10),    // min TTL (healthy)
 *         Duration.ofMinutes(30)     // max TTL (critical)
 *     ))
 *     .build();
 * 
 * // Record latency
 * try (var sample = lifecache.startSample()) {
 *     result = callBackend();
 * }
 * 
 * // Get adaptive TTL
 * Duration ttl = lifecache.getStaleness();
 * }</pre>
 * 
 * @author Weihua Zhu
 */
public class Lifecache implements AutoCloseable {
    
    private final MetricsCollector metricsCollector;
    private final HealthPolicy healthPolicy;
    private final Map<String, Function<Double, ?>> breakdowns;
    
    private Lifecache(Builder builder) {
        this.metricsCollector = builder.metricsCollector;
        this.healthPolicy = builder.healthPolicy;
        this.breakdowns = new HashMap<>(builder.breakdowns);
    }
    
    // ============ Factory Methods for Staleness ============
    
    /**
     * Linear interpolation: health 1.0 → min, health 0.0 → max
     */
    public static Function<Double, Duration> linear(Duration min, Duration max) {
        return health -> {
            long minMs = min.toMillis();
            long maxMs = max.toMillis();
            long ttlMs = (long) (minMs + (maxMs - minMs) * (1.0 - health));
            return Duration.ofMillis(ttlMs);
        };
    }
    
    /**
     * Step function with custom thresholds.
     * 
     * <pre>{@code
     * Lifecache.stepFunction(
     *     entry(1.0, Duration.ofSeconds(10)),   // healthy → 10s
     *     entry(0.5, Duration.ofMinutes(5)),    // degraded → 5min
     *     entry(0.0, Duration.ofMinutes(30))    // critical → 30min
     * )
     * }</pre>
     */
    @SafeVarargs
    public static Function<Double, Duration> stepFunction(Map.Entry<Double, Duration>... thresholds) {
        List<Map.Entry<Double, Duration>> sorted = Arrays.stream(thresholds)
            .sorted((a, b) -> Double.compare(b.getKey(), a.getKey()))  // descending by health
            .toList();
        
        return health -> {
            if (sorted.isEmpty()) {
                return Duration.ofMinutes(10);
            }
            
            // Find surrounding thresholds and interpolate
            for (int i = 0; i < sorted.size() - 1; i++) {
                var upper = sorted.get(i);
                var lower = sorted.get(i + 1);
                
                if (health <= upper.getKey() && health >= lower.getKey()) {
                    double ratio = (upper.getKey() - health) / (upper.getKey() - lower.getKey());
                    long upperMs = upper.getValue().toMillis();
                    long lowerMs = lower.getValue().toMillis();
                    long ttlMs = (long) (upperMs + (lowerMs - upperMs) * ratio);
                    return Duration.ofMillis(ttlMs);
                }
            }
            
            // Above highest threshold
            if (health >= sorted.get(0).getKey()) {
                return sorted.get(0).getValue();
            }
            
            // Below lowest threshold
            return sorted.get(sorted.size() - 1).getValue();
        };
    }
    
    /**
     * Sigmoid curve: smooth S-shaped transition
     */
    public static Function<Double, Duration> sigmoid(Duration min, Duration max, double steepness) {
        return health -> {
            double x = (0.5 - health) * steepness;
            double weight = 1.0 / (1.0 + Math.exp(-x));
            long minMs = min.toMillis();
            long maxMs = max.toMillis();
            long ttlMs = (long) (minMs + (maxMs - minMs) * weight);
            return Duration.ofMillis(ttlMs);
        };
    }
    
    /**
     * Helper to create map entries for step function.
     */
    public static Map.Entry<Double, Duration> entry(double health, Duration duration) {
        return Map.entry(health, duration);
    }
    
    // ============ Factory Methods for Drop Rate ============
    
    /**
     * Linear drop rate: starts at threshold, increases to maxRate at health=0
     */
    public static Function<Double, Double> dropRate(double startThreshold, double maxRate) {
        return health -> {
            if (health >= startThreshold) {
                return 0.0;
            }
            double ratio = (startThreshold - health) / startThreshold;
            return Math.min(maxRate, ratio * maxRate);
        };
    }
    
    // ============ Builder ============
    
    public static Builder builder() {
        return new Builder();
    }
    
    // ============ Latency Recording ============
    
    /**
     * Start a latency sample. Use in try-with-resources.
     */
    public MetricsCollector.Sample startSample() {
        return metricsCollector.startSample();
    }
    
    /**
     * Record a latency sample directly.
     */
    public void record(double latencyMs) {
        metricsCollector.record(latencyMs);
    }
    
    // ============ Core Output ============
    
    /**
     * 🎯 THE CORE OUTPUT: Health score (0.0 = critical, 1.0 = healthy)
     * 
     * <p>This is the single source of truth. All breakdowns are derived from this.</p>
     * 
     * <pre>{@code
     * double health = lifecache.getHealthScore();
     * 
     * // Use breakdowns (user-defined names)
     * Duration ttl = lifecache.getBreakdown("cacheTtl");
     * int priority = lifecache.getBreakdown("priority");
     * 
     * // Probabilistic drop
     * if (lifecache.shouldDrop("loadShedding")) { return; }
     * }</pre>
     */
    public double getHealthScore() {
        return healthPolicy.evaluate(metricsCollector);
    }
    
    // ============ Breakdowns (all derived from healthScore) ============
    
    /**
     * Get a breakdown value by name.
     * 
     * <pre>{@code
     * Duration ttl = lifecache.getBreakdown("cacheTtl");
     * int priority = lifecache.getBreakdown("priority");
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    public <T> T getBreakdown(String name) {
        Function<Double, ?> fn = breakdowns.get(name);
        if (fn == null) {
            throw new IllegalArgumentException("Unknown breakdown: " + name + 
                ". Available: " + breakdowns.keySet());
        }
        return (T) fn.apply(getHealthScore());
    }
    
    /**
     * Should this request be dropped? Probabilistic based on breakdown value.
     * 
     * <p>The breakdown must return a Double (0.0 - 1.0 drop probability).
     * When health < threshold, drop probability increases as health decreases.</p>
     * 
     * <pre>{@code
     * // Configure: health < 0.7 → start dropping, up to 90% at health=0
     * .breakdown("loadShedding", Lifecache.dropRate(0.7, 0.9))
     * 
     * // Use
     * if (lifecache.shouldDrop("loadShedding")) {
     *     return FALLBACK_VALUE;  // Drop this request
     * }
     * }</pre>
     */
    public boolean shouldDrop(String name) {
        if (!hasBreakdown(name)) {
            return false;
        }
        double rate = getBreakdown(name);
        return rate > 0 && Math.random() < rate;
    }
    
    /**
     * Check if a breakdown exists.
     */
    public boolean hasBreakdown(String name) {
        return breakdowns.containsKey(name);
    }
    
    /**
     * Get all registered breakdown names.
     */
    public Set<String> getBreakdownNames() {
        return Collections.unmodifiableSet(breakdowns.keySet());
    }
    
    // ============ Metrics ============
    
    /**
     * Get current P95 latency
     */
    public double getP95Latency() {
        return metricsCollector.getP95();
    }
    
    /**
     * Get metrics snapshot (for debugging/monitoring)
     */
    public Metrics getMetrics() {
        return new Metrics(
            metricsCollector.getP50(),
            metricsCollector.getP95(),
            metricsCollector.getP99(),
            metricsCollector.getAverage(),
            metricsCollector.getSampleCount(),
            getHealthScore()
        );
    }
    
    @Override
    public void close() {
        // No resources to clean up in base implementation
    }
    
    // ============ Records ============
    
    /**
     * Metrics snapshot (for debugging/monitoring only)
     */
    public record Metrics(
        double p50Latency,
        double p95Latency,
        double p99Latency,
        double avgLatency,
        int sampleCount,
        double healthScore
    ) {
        public String getStatus() {
            if (healthScore >= 0.8) return "HEALTHY";
            if (healthScore >= 0.5) return "DEGRADED";
            if (healthScore >= 0.2) return "STRESSED";
            return "CRITICAL";
        }
    }
    
    // ============ Builder ============
    
    public static class Builder {
        private MetricsCollector metricsCollector;
        private HealthPolicy healthPolicy;
        private Duration metricsWindow = Duration.ofSeconds(10);
        
        private final List<double[]> latencyThresholds = new ArrayList<>();
        private final Map<String, Function<Double, ?>> breakdowns = new HashMap<>();
        
        /**
         * Register a breakdown function with user-defined name.
         * 
         * <pre>{@code
         * Lifecache lifecache = Lifecache.builder()
         *     // Cache TTL: health 1.0 → 10s, health 0.0 → 30min
         *     .breakdown("cacheTtl", Lifecache.linear(Duration.ofSeconds(10), Duration.ofMinutes(30)))
         *     
         *     // Load shedding: health < 0.7 → start dropping, up to 90% at health=0
         *     .breakdown("loadShedding", Lifecache.dropRate(0.7, 0.9))
         *     
         *     // Custom logic
         *     .breakdown("priority", health -> health > 0.8 ? 1 : health > 0.5 ? 2 : 3)
         *     .build();
         * 
         * // Use
         * Duration ttl = lifecache.getBreakdown("cacheTtl");
         * if (lifecache.shouldDrop("loadShedding")) { return; }
         * int priority = lifecache.getBreakdown("priority");
         * }</pre>
         * 
         * @param name your strategy name
         * @param function health score (0.0-1.0) → value
         */
        public <T> Builder breakdown(String name, Function<Double, T> function) {
            this.breakdowns.put(name, function);
            return this;
        }
        
        /**
         * Add a latency threshold for health calculation.
         * 
         * @param latencyMs P95 latency threshold in milliseconds
         * @param healthScore health score at this threshold (1.0 = healthy, 0.0 = critical)
         */
        public Builder latencyThreshold(double latencyMs, double healthScore) {
            latencyThresholds.add(new double[]{latencyMs, healthScore});
            return this;
        }
        
        /**
         * Metrics collection window (default: 10 seconds)
         */
        public Builder metricsWindow(Duration window) {
            this.metricsWindow = window;
            return this;
        }
        
        /**
         * Use custom metrics collector
         */
        public Builder metricsCollector(MetricsCollector collector) {
            this.metricsCollector = collector;
            return this;
        }
        
        /**
         * Use custom health policy
         */
        public Builder healthPolicy(HealthPolicy policy) {
            this.healthPolicy = policy;
            return this;
        }
        
        public Lifecache build() {
            if (metricsCollector == null) {
                metricsCollector = new SlidingWindowCollector(metricsWindow);
            }
            
            if (healthPolicy == null) {
                if (latencyThresholds.isEmpty()) {
                    healthPolicy = new StepFunctionPolicy();
                } else {
                    double[][] arr = latencyThresholds.toArray(new double[0][]);
                    healthPolicy = new StepFunctionPolicy(arr);
                }
            }
            
            return new Lifecache(this);
        }
    }
}
