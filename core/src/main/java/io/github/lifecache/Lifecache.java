package io.github.lifecache;

import io.github.lifecache.decision.*;
import io.github.lifecache.metrics.*;
import io.github.lifecache.policy.QoSEvaluator;
import io.github.lifecache.policy.StepFunctionEvaluator;

import java.time.Duration;
import java.util.*;
import java.util.function.Function;

/**
 * 🚢 Lifecache - Quality-aware caching that keeps your system afloat
 * 
 * <h2>Quick Start</h2>
 * <pre>{@code
 * Lifecache lifecache = Lifecache.builder()
 *     .metricsWindow(Duration.ofSeconds(60))
 *     .metricName("latency")
 *     .aggregation(Aggregation.P95)
 *     .threshold(100, 1.0)   // P95 < 100ms = healthy
 *     .threshold(300, 0.5)   // P95 = 300ms = degraded
 *     .threshold(500, 0.0)   // P95 > 500ms = critical
 *     .rule("allowStaleness", DecisionRule.stepsWithInterpolation(
 *         DecisionRule.step(1.0, Duration.ofSeconds(10)),
 *         DecisionRule.step(0.0, Duration.ofMinutes(30))
 *     ))
 *     .rule("throttleRate", DecisionRule.stepsWithDoubleInterpolation(
 *         DecisionRule.step(0.5, 0.0),
 *         DecisionRule.step(0.0, 0.9)
 *     ))
 *     .build();
 * 
 * // Record metrics
 * lifecache.record(MetricSample.of("latency", 150));
 * 
 * // Get adaptive decisions (each rule generates one typed result)
 * QoSDecision<Duration> ttlDecision = lifecache.getDecision("allowStaleness");
 * Duration ttl = ttlDecision.value();
 * 
 * QoSDecision<Double> throttleDecision = lifecache.getDecision("throttleRate");
 * if (lifecache.shouldThrottle()) { return fallback; }
 * }</pre>
 * 
 * @author Weihua Zhu
 */
public class Lifecache implements AutoCloseable {
    
    private final MetricsRegistry metricsRegistry;
    private final QoSEvaluator qosEvaluator;
    private final Map<String, Function<Double, ?>> breakdowns;
    
    // Generic decision rules (JSON-configurable)
    private final Map<String, DecisionRule<?>> decisionRules;
    
    private Lifecache(Builder builder) {
        this.metricsRegistry = builder.metricsRegistry;
        this.qosEvaluator = builder.qosEvaluator;
        this.breakdowns = new HashMap<>(builder.breakdowns);
        this.decisionRules = new HashMap<>(builder.decisionRules);
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
    
    // ============ Metrics Recording ============
    
    /**
     * Record a metric sample.
     */
    public void record(MetricSample sample) {
        metricsRegistry.record(sample);
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
        return qosEvaluator.evaluate();
    }
    
    // ============ Decision API (using generic DecisionRule) ============
    
    /**
     * Get decision for a specific rule.
     * 
     * <pre>{@code
     * QoSDecision<Duration> ttlDecision = lifecache.getDecision("allowStaleness");
     * Duration ttl = ttlDecision.value();
     * 
     * QoSDecision<Double> throttleDecision = lifecache.getDecision("throttleRate");
     * Double rate = throttleDecision.value();
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    public <T> QoSDecision<T> getDecision(String ruleName) {
        DecisionRule<?> rule = decisionRules.get(ruleName);
        if (rule == null) {
            throw new IllegalArgumentException("Unknown rule: " + ruleName + 
                ". Available: " + decisionRules.keySet());
        }
        double health = getHealthScore();
        return (QoSDecision<T>) rule.evaluate(health);
    }
    
    /**
     * Get a specific decision value (shortcut for getDecision(name).value()).
     */
    @SuppressWarnings("unchecked")
    public <T> T getDecisionValue(String name) {
        DecisionRule<?> rule = decisionRules.get(name);
        if (rule == null) {
            return null;
        }
        return (T) rule.apply(getHealthScore());
    }
    
    /**
     * Convenience: should this request be throttled? (probabilistic based on throttleRate)
     */
    public boolean shouldThrottle() {
        Double rate = getDecisionValue("throttleRate");
        if (rate == null || rate <= 0) return false;
        return Math.random() < rate;
    }
    
    /**
     * Convenience: get current soft TTL.
     */
    public Duration getAllowStaleness() {
        return getDecisionValue("allowStaleness");
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
    
    // ============ Accessors ============
    
    /**
     * Get underlying metrics registry (for advanced use).
     */
    public MetricsRegistry getMetricsRegistry() {
        return metricsRegistry;
    }
    
    /**
     * Get current metrics snapshot.
     */
    public Metrics getMetrics() {
        double health = getHealthScore();
        Duration window = Duration.ofSeconds(30); // Default reading window
        
        Double p50 = metricsRegistry.read("latency", Aggregation.P50, window);
        Double p95 = metricsRegistry.read("latency", Aggregation.P95, window);
        Double p99 = metricsRegistry.read("latency", Aggregation.P99, window);
        
        return new Metrics(
            p50 != null ? p50 : 0.0,
            p95 != null ? p95 : 0.0,
            p99 != null ? p99 : 0.0,
            health
        );
    }
    
    /**
     * Metrics snapshot with latency percentiles and health score.
     */
    public record Metrics(
        double p50Latency,
        double p95Latency,
        double p99Latency,
        double healthScore
    ) {
        /**
         * Get health status string.
         */
        public String getStatus() {
            if (healthScore >= 0.9) return "HEALTHY";
            if (healthScore >= 0.7) return "DEGRADED";
            if (healthScore >= 0.4) return "STRESSED";
            return "CRITICAL";
        }
    }
    
    @Override
    public void close() {
        // No resources to clean up in base implementation
    }
    
    // ============ Builder ============
    
    public static class Builder {
        private MetricsRegistry metricsRegistry;
        private QoSEvaluator qosEvaluator;
        private Duration metricsWindow = Duration.ofSeconds(10);
        
        // Evaluator config
        private String metricName;
        private Aggregation aggregation;
        private final List<double[]> thresholds = new ArrayList<>();
        
        private final Map<String, Function<Double, ?>> breakdowns = new HashMap<>();
        
        // Generic decision rules (JSON-configurable)
        private final Map<String, DecisionRule<?>> decisionRules = new HashMap<>();
        
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
         * Set metric name to evaluate.
         */
        public Builder metricName(String name) {
            this.metricName = name;
            return this;
        }
        
        /**
         * Set aggregation type (P95, P99, AVG, etc.).
         */
        public Builder aggregation(Aggregation agg) {
            this.aggregation = agg;
            return this;
        }
        
        /**
         * Add a threshold for health calculation.
         * 
         * @param value metric value threshold
         * @param healthScore health score at this threshold (1.0 = healthy, 0.0 = critical)
         */
        public Builder threshold(double value, double healthScore) {
            thresholds.add(new double[]{value, healthScore});
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
         * Use custom metrics registry
         */
        public Builder metricsRegistry(MetricsRegistry registry) {
            this.metricsRegistry = registry;
            return this;
        }
        
        /**
         * Use custom QoS evaluator
         */
        public Builder qosEvaluator(QoSEvaluator evaluator) {
            this.qosEvaluator = evaluator;
            return this;
        }
        
        // ============ Generic Decision Rules ============
        
        /**
         * Add a decision rule by name.
         * 
         * <pre>{@code
         * .rule("allowStaleness", DecisionRule.stepsWithInterpolation(step(1.0, 30s), step(0.0, 30min)))
         * .rule("throttleRate", DecisionRule.stepsWithDoubleInterpolation(step(0.5, 0.0), step(0.0, 0.9)))
         * .rule("priority", DecisionRule.steps(step(0.8, 1), step(0.5, 2), step(0.0, 3)))
         * }</pre>
         */
        public <T> Builder rule(String name, DecisionRule<T> rule) {
            this.decisionRules.put(name, rule);
            return this;
        }
        
        /**
         * Add all rules from a map.
         */
        public Builder rules(Map<String, DecisionRule<?>> rules) {
            this.decisionRules.putAll(rules);
            return this;
        }
        
        /**
         * Set throttle rule (convenience for .rule("throttleRate", ...))
         * 
         * @param startThreshold health below which throttling starts
         * @param maxRate maximum throttle rate at health=0
         */
        public Builder throttle(double startThreshold, double maxRate) {
            return rule("throttleRate", DecisionRule.stepsWithDoubleInterpolation(
                DecisionRule.step(startThreshold, 0.0),
                DecisionRule.step(0.0, maxRate)
            ));
        }
        
        /**
         * Set AllowStaleness rule (convenience for .rule("allowStaleness", ...))
         * 
         * @param minTTL TTL when healthy
         * @param maxTTL TTL when critical
         */
        public Builder allowStaleness(Duration minTTL, Duration maxTTL) {
            return rule("allowStaleness", DecisionRule.stepsWithInterpolation(
                DecisionRule.step(1.0, minTTL),
                DecisionRule.step(0.0, maxTTL)
            ));
        }
        
        public Lifecache build() {
            // Validate required fields
            if (metricName == null) {
                throw new IllegalStateException("metricName is required");
            }
            if (aggregation == null) {
                throw new IllegalStateException("aggregation is required");
            }
            if (thresholds.isEmpty()) {
                throw new IllegalStateException("At least one threshold is required");
            }
            
            if (metricsRegistry == null) {
                metricsRegistry = new SlidingWindowRegistry(metricsWindow);
            }
            
            if (qosEvaluator == null) {
                double[][] arr = thresholds.toArray(new double[0][]);
                qosEvaluator = StepFunctionEvaluator.builder()
                    .metricsReader(metricsRegistry)
                    .metricName(metricName)
                    .aggregation(aggregation)
                    .window(metricsWindow)
                    .thresholds(arr)
                    .build();
            }
            
            // Add defaults if not configured
            if (!decisionRules.containsKey("allowStaleness")) {
                decisionRules.put("allowStaleness", DecisionRule.stepsWithInterpolation(
                    DecisionRule.step(1.0, Duration.ofMinutes(1)),
                    DecisionRule.step(0.5, Duration.ofMinutes(5)),
                    DecisionRule.step(0.0, Duration.ofMinutes(30))
                ));
            }
            if (!decisionRules.containsKey("throttleRate")) {
                decisionRules.put("throttleRate", DecisionRule.stepsWithDoubleInterpolation(
                    DecisionRule.step(0.5, 0.0),
                    DecisionRule.step(0.3, 0.5),
                    DecisionRule.step(0.0, 0.9)
                ));
            }
            
            return new Lifecache(this);
        }
    }
}
