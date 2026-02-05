package io.github.lifecache.config;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Complete JSON configuration for Adaptive QoS system.
 * Single JSON file to configure entire system.
 * 
 * <h3>Metric Types</h3>
 * <ul>
 *   <li><b>GAUGE</b>: Point-in-time values (latency, error_rate) - use P50, P95, P99, AVG</li>
 *   <li><b>COUNTER</b>: Cumulative values (request_count, bytes) - use SUM, RATE, COUNT</li>
 * </ul>
 * 
 * <h3>Per-Signal Configuration</h3>
 * <p>Each signal has its own:</p>
 * <ul>
 *   <li><b>type</b>: GAUGE or COUNTER</li>
 *   <li><b>bucketSeconds × maxBuckets</b>: Sliding window size</li>
 *   <li><b>processor</b>: Filtering rules (dropNegative, maxValue, minValue)</li>
 * </ul>
 * 
 * <p>Example JSON:</p>
 * <pre>{@code
 * {
 *   "registry": {
 *     "signalStores": [
 *       {
 *         "name": "grpc_latency",
 *         "type": "GAUGE",
 *         "bucketSeconds": 10,
 *         "maxBuckets": 6,
 *         "aggregations": ["P50", "P95", "P99"],
 *         "processor": {
 *           "dropNegative": true,
 *           "maxValue": 60000
 *         }
 *       },
 *       {
 *         "name": "request_count",
 *         "type": "COUNTER",
 *         "bucketSeconds": 10,
 *         "maxBuckets": 6,
 *         "aggregations": ["SUM", "RATE"],
 *         "processor": {
 *           "dropNegative": true
 *         }
 *       },
 *       {
 *         "name": "error_rate",
 *         "type": "GAUGE",
 *         "aggregations": ["AVG"],
 *         "processor": {
 *           "dropNegative": true,
 *           "maxValue": 1.0,
 *           "minValue": 0.0
 *         }
 *       }
 *     ]
 *   },
 *   "evaluator": {
 *     "rules": [
 *       {
 *         "metricName": "grpc_latency",
 *         "percentile": "P95",
 *         "weight": 0.6,
 *         "stepFunction": {
 *           "thresholds": [
 *             { "value": 100, "score": 1.0 },
 *             { "value": 500, "score": 0.0 }
 *           ],
 *           "interpolation": "LINEAR"
 *         }
 *       }
 *     ]
 *   },
 *   "decisions": {
 *     "allowStaleness": {
 *       "type": "DURATION",
 *       "steps": [
 *         { "healthMin": 1.0, "value": 30 },
 *         { "healthMin": 0.0, "value": 1800 }
 *       ],
 *       "interpolation": "LINEAR"
 *     }
 *   }
 * }
 * }</pre>
 */
public record AdaptiveQoSConfig(
    RegistryConfig registry,
    EvaluatorConfig evaluator,
    Map<String, DecisionConfig> decisions,
    Map<String, DerivedMetricConfig> derivedMetrics,
    Map<String, Object> metadata
) {
    
    public AdaptiveQoSConfig {
        if (registry == null) {
            registry = RegistryConfig.defaults();
        }
        if (evaluator == null) {
            evaluator = EvaluatorConfig.defaults();
        }
        if (decisions == null) {
            decisions = DecisionConfig.defaults();
        }
        if (derivedMetrics == null) {
            derivedMetrics = Map.of();
        }
        if (metadata == null) {
            metadata = Map.of();
        }
    }
    
    /**
     * Create default configuration.
     */
    public static AdaptiveQoSConfig defaults() {
        return new AdaptiveQoSConfig(
            RegistryConfig.defaults(),
            EvaluatorConfig.defaults(),
            DecisionConfig.defaults(),
            Map.of(),
            Map.of("version", "1.0")
        );
    }
    
    /**
     * Alias for backward compatibility.
     */
    public RegistryConfig collector() {
        return registry;
    }
    
    // ============ Registry Config ============
    
    /**
     * Registry configuration.
     * Contains signal stores with per-signal configuration.
     */
    public record RegistryConfig(
        String type,
        List<SignalStoreConfig> signalStores
    ) {
        public RegistryConfig {
            if (type == null || type.isBlank()) {
                type = "ROUTED";
            }
            if (signalStores == null) {
                signalStores = List.of();
            }
        }
        
        public static RegistryConfig defaults() {
            return new RegistryConfig(
                "ROUTED",
                List.of(SignalStoreConfig.latency())
            );
        }
    }
    
    // ============ SignalStore Config (per signal) ============
    
    /**
     * Per-signal configuration.
     * Each signal has its own:
     * <ul>
     *   <li>type: GAUGE (percentiles) or COUNTER (sum/rate)</li>
     *   <li>bucket size and window</li>
     *   <li>processor rules (drop negative, max value, etc.)</li>
     * </ul>
     * 
     * <p>Example JSON:</p>
     * <pre>{@code
     * {
     *   "name": "grpc_latency",
     *   "type": "GAUGE",
     *   "bucketSeconds": 10,
     *   "maxBuckets": 6,
     *   "aggregations": ["P50", "P95", "P99"],
     *   "processor": {
     *     "dropNegative": true,
     *     "maxValue": 60000
     *   }
     * }
     * }</pre>
     */
    public record SignalStoreConfig(
        String name,
        String type,              // "GAUGE" or "COUNTER"
        int bucketSeconds,
        int maxBuckets,
        List<String> aggregations, // For GAUGE: P50, P95, P99, AVG; For COUNTER: SUM, RATE, COUNT
        ProcessorConfig processor  // Per-signal processor
    ) {
        public SignalStoreConfig {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("SignalStore name is required");
            }
            if (type == null || type.isBlank()) {
                type = "GAUGE";
            }
            if (bucketSeconds <= 0) {
                bucketSeconds = 10;
            }
            if (maxBuckets <= 0) {
                maxBuckets = 6;
            }
            if (aggregations == null) {
                aggregations = type.equals("COUNTER") 
                    ? List.of("SUM", "RATE", "COUNT")
                    : List.of("P50", "P95", "P99", "AVG");
            }
            if (processor == null) {
                processor = ProcessorConfig.defaults();
            }
        }
        
        /**
         * Create GAUGE signal config for latency.
         */
        public static SignalStoreConfig latency() {
            return gauge("latency", List.of("P50", "P95", "P99", "AVG"))
                .withProcessor(ProcessorConfig.forLatency());
        }
        
        /**
         * Create GAUGE signal config.
         */
        public static SignalStoreConfig gauge(String name) {
            return new SignalStoreConfig(name, "GAUGE", 10, 6, 
                List.of("P95"), ProcessorConfig.defaults());
        }
        
        /**
         * Create GAUGE signal config with specific aggregations.
         */
        public static SignalStoreConfig gauge(String name, List<String> aggregations) {
            return new SignalStoreConfig(name, "GAUGE", 10, 6, 
                aggregations, ProcessorConfig.defaults());
        }
        
        /**
         * Create COUNTER signal config.
         */
        public static SignalStoreConfig counter(String name) {
            return new SignalStoreConfig(name, "COUNTER", 10, 6,
                List.of("SUM", "RATE", "COUNT"), ProcessorConfig.defaults());
        }
        
        /**
         * Builder-style: set processor.
         */
        public SignalStoreConfig withProcessor(ProcessorConfig processor) {
            return new SignalStoreConfig(name, type, bucketSeconds, maxBuckets, aggregations, processor);
        }
        
        /**
         * Builder-style: set window.
         */
        public SignalStoreConfig withWindow(int bucketSeconds, int maxBuckets) {
            return new SignalStoreConfig(name, type, bucketSeconds, maxBuckets, aggregations, processor);
        }
        
        /**
         * Is this a GAUGE metric?
         */
        public boolean isGauge() {
            return "GAUGE".equalsIgnoreCase(type);
        }
        
        /**
         * Is this a COUNTER metric?
         */
        public boolean isCounter() {
            return "COUNTER".equalsIgnoreCase(type);
        }
        
        /**
         * Total window duration = bucketSeconds × maxBuckets
         */
        public Duration window() {
            return Duration.ofSeconds((long) bucketSeconds * maxBuckets);
        }
    }
    
    // ============ Processor Config (per signal) ============
    
    /**
     * Per-signal processor configuration for filtering and validation.
     */
    public record ProcessorConfig(
        boolean dropNegative,
        Double maxValue,
        Double minValue
    ) {
        public ProcessorConfig {
            // Defaults handled in record constructor
        }
        
        public static ProcessorConfig defaults() {
            return new ProcessorConfig(true, null, null);  // Drop negative by default
        }
        
        /**
         * Config for latency metrics: drop negative, max 60s.
         */
        public static ProcessorConfig forLatency() {
            return new ProcessorConfig(true, 60000.0, null);
        }
        
        /**
         * Config for rate metrics: drop negative, 0-1 range.
         */
        public static ProcessorConfig forRate() {
            return new ProcessorConfig(true, 1.0, 0.0);
        }
        
        /**
         * Config for counter metrics: drop negative only.
         */
        public static ProcessorConfig forCounter() {
            return new ProcessorConfig(true, null, null);
        }
    }
    
    // ============ Derived Metric Config ============
    
    /**
     * Configuration for a derived (computed) metric.
     *
     * <p>Example JSON:</p>
     * <pre>{@code
     * "derivedMetrics": {
     *   "error_rate": {
     *     "formula": "error_count / (error_count + success_count)",
     *     "aggregation": "SUM",
     *     "windowSeconds": 300
     *   },
     *   "cache_hit_rate": {
     *     "formula": "cache_hits / (cache_hits + cache_misses)",
     *     "aggregation": "SUM",
     *     "windowSeconds": 60
     *   }
     * }
     * }</pre>
     *
     * <p>Supported operators: +, -, *, / and parentheses.</p>
     */
    public record DerivedMetricConfig(
        String formula,
        String aggregation,
        int windowSeconds
    ) {
        public DerivedMetricConfig {
            if (formula == null || formula.isBlank()) {
                throw new IllegalArgumentException("formula is required");
            }
            if (aggregation == null || aggregation.isBlank()) {
                aggregation = "SUM";
            }
            if (windowSeconds <= 0) {
                windowSeconds = 300; // Default: 5 minutes
            }
        }
        
        /**
         * Get window as Duration.
         */
        public Duration window() {
            return Duration.ofSeconds(windowSeconds);
        }
        
        /**
         * Error rate config: errors / (errors + successes).
         */
        public static DerivedMetricConfig errorRate() {
            return new DerivedMetricConfig(
                "error_count / (error_count + success_count)",
                "SUM",
                300
            );
        }
        
        /**
         * Cache hit rate config: hits / (hits + misses).
         */
        public static DerivedMetricConfig cacheHitRate() {
            return new DerivedMetricConfig(
                "cache_hits / (cache_hits + cache_misses)",
                "SUM",
                60
            );
        }
    }
    
    // ============ Evaluator Config ============
    
    public record EvaluatorConfig(
        List<RuleConfig> rules,
        String aggregation
    ) {
        public EvaluatorConfig {
            if (rules == null) {
                rules = List.of();
            }
            if (aggregation == null || aggregation.isBlank()) {
                aggregation = "WEIGHTED_AVG";
            }
        }
        
        public static EvaluatorConfig defaults() {
            return new EvaluatorConfig(
                List.of(RuleConfig.latencyP95()),
                "WEIGHTED_AVG"
            );
        }
    }
    
    public record RuleConfig(
        String metricName,
        String percentile,
        int windowSeconds,
        double weight,
        boolean isDerived,
        StepFunctionConfig stepFunction
    ) {
        public RuleConfig {
            if (metricName == null || metricName.isBlank()) {
                throw new IllegalArgumentException("Metric name is required");
            }
            // For derived metrics, percentile and windowSeconds are ignored
            if (!isDerived) {
                if (percentile == null || percentile.isBlank()) {
                    percentile = "P95";
                }
                if (windowSeconds <= 0) {
                    windowSeconds = 30;
                }
            }
            if (weight <= 0) {
                weight = 1.0;
            }
            if (stepFunction == null) {
                stepFunction = StepFunctionConfig.defaultLatency();
            }
        }
        
        /**
         * Get window as Duration.
         */
        public java.time.Duration window() {
            return java.time.Duration.ofSeconds(windowSeconds);
        }
        
        public static RuleConfig latencyP95() {
            return new RuleConfig("latency", "P95", 30, 1.0, false, StepFunctionConfig.defaultLatency());
        }
        
        /**
         * Create a rule for a derived metric.
         */
        public static RuleConfig derived(String metricName, double weight, StepFunctionConfig stepFunction) {
            return new RuleConfig(metricName, null, 0, weight, true, stepFunction);
        }
    }
    
    // ============ Step Function Config ============
    
    public record StepFunctionConfig(
        List<ThresholdConfig> thresholds,
        String interpolation
    ) {
        public StepFunctionConfig {
            if (thresholds == null || thresholds.isEmpty()) {
                thresholds = List.of(
                    new ThresholdConfig(100, 1.0),
                    new ThresholdConfig(500, 0.0)
                );
            }
            if (interpolation == null || interpolation.isBlank()) {
                interpolation = "LINEAR";
            }
        }
        
        public static StepFunctionConfig defaultLatency() {
            return new StepFunctionConfig(
                List.of(
                    new ThresholdConfig(100, 1.0),
                    new ThresholdConfig(200, 0.75),
                    new ThresholdConfig(300, 0.5),
                    new ThresholdConfig(400, 0.25),
                    new ThresholdConfig(500, 0.0)
                ),
                "LINEAR"
            );
        }
        
        public static StepFunctionConfig forErrorRate() {
            return new StepFunctionConfig(
                List.of(
                    new ThresholdConfig(0.01, 1.0),
                    new ThresholdConfig(0.05, 0.5),
                    new ThresholdConfig(0.1, 0.0)
                ),
                "LINEAR"
            );
        }
    }
    
    public record ThresholdConfig(
        double value,
        double score
    ) {
        public ThresholdConfig {
            if (score < 0 || score > 1) {
                throw new IllegalArgumentException("Score must be 0.0 - 1.0");
            }
        }
    }
    
    // ============ Decision Config (Step Function Only) ============
    
    /**
     * Decision configuration using step function.
     * 
     * <pre>{@code
     * {
     *   "allowStaleness": {
     *     "type": "DURATION",
     *     "steps": [
     *       { "healthMin": 1.0, "value": 30 },
     *       { "healthMin": 0.5, "value": 300 },
     *       { "healthMin": 0.0, "value": 1800 }
     *     ],
     *     "interpolation": "LINEAR"
     *   }
     * }
     * }</pre>
     */
    public record DecisionConfig(
        String type,
        List<StepConfig> steps,
        String interpolation
    ) {
        public DecisionConfig {
            if (type == null || type.isBlank()) {
                type = "DOUBLE";
            }
            if (steps == null || steps.isEmpty()) {
                steps = List.of(new StepConfig(1.0, 0), new StepConfig(0.0, 1));
            }
            if (interpolation == null || interpolation.isBlank()) {
                interpolation = "STEP";  // or "LINEAR" for interpolation
            }
        }
        
        public static Map<String, DecisionConfig> defaults() {
            Map<String, DecisionConfig> decisions = new LinkedHashMap<>();
            decisions.put("allowStaleness", new DecisionConfig("DURATION", 
                List.of(
                    new StepConfig(1.0, 30),
                    new StepConfig(0.5, 300),
                    new StepConfig(0.0, 1800)
                ), "LINEAR"));
            decisions.put("throttleRate", new DecisionConfig("DOUBLE", 
                List.of(
                    new StepConfig(0.5, 0.0),
                    new StepConfig(0.3, 0.5),
                    new StepConfig(0.0, 0.9)
                ), "LINEAR"));
            return decisions;
        }
        
        /**
         * Create a duration decision with steps.
         */
        public static DecisionConfig duration(StepConfig... steps) {
            return new DecisionConfig("DURATION", Arrays.asList(steps), "LINEAR");
        }
        
        /**
         * Create a double decision with steps.
         */
        public static DecisionConfig rate(StepConfig... steps) {
            return new DecisionConfig("DOUBLE", Arrays.asList(steps), "LINEAR");
        }
        
        /**
         * Create an integer decision with steps.
         */
        public static DecisionConfig integer(StepConfig... steps) {
            return new DecisionConfig("INTEGER", Arrays.asList(steps), "STEP");
        }
        
        /**
         * Create a boolean decision with steps.
         */
        public static DecisionConfig bool(StepConfig... steps) {
            return new DecisionConfig("BOOLEAN", Arrays.asList(steps), "STEP");
        }
    }
    
    /**
     * A step in the step function.
     */
    public record StepConfig(
        double healthMin,
        Object value
    ) {
        /**
         * Create a step.
         */
        public static StepConfig of(double healthMin, Object value) {
            return new StepConfig(healthMin, value);
        }
    }
}
