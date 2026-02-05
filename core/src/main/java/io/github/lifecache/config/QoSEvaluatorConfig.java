package io.github.lifecache.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * JSON-serializable QoS Evaluator Configuration.
 * 
 * <p>Example JSON:</p>
 * <pre>{@code
 * {
 *   "healthRules": [
 *     {
 *       "metricName": "grpc_latency",
 *       "percentile": "P95",
 *       "windowSeconds": 30,
 *       "weight": 0.6,
 *       "thresholds": [
 *         { "value": 100, "healthScore": 1.0 },
 *         { "value": 200, "healthScore": 0.75 },
 *         { "value": 300, "healthScore": 0.5 },
 *         { "value": 500, "healthScore": 0.0 }
 *       ]
 *     },
 *     {
 *       "metricName": "error_rate",
 *       "percentile": "AVG",
 *       "windowSeconds": 300,
 *       "weight": 0.4,
 *       "thresholds": [
 *         { "value": 0.01, "healthScore": 1.0 },
 *         { "value": 0.05, "healthScore": 0.5 },
 *         { "value": 0.1, "healthScore": 0.0 }
 *       ]
 *     }
 *   ],
 *   "aggregation": "WEIGHTED_MIN"
 * }
 * }</pre>
 */
public record QoSEvaluatorConfig(
    List<HealthRule> healthRules,
    String aggregation
) {
    
    public QoSEvaluatorConfig {
        if (healthRules == null) {
            healthRules = List.of();
        }
        if (aggregation == null || aggregation.isBlank()) {
            aggregation = "WEIGHTED_AVG";
        }
    }
    
    /**
     * Create default config for latency-based evaluation.
     */
    public static QoSEvaluatorConfig defaultLatencyConfig() {
        return new QoSEvaluatorConfig(
            List.of(new HealthRule(
                "latency",
                "P95",
                30,  // 30 seconds window
                1.0,
                List.of(
                    new Threshold(100, 1.0),
                    new Threshold(200, 0.75),
                    new Threshold(300, 0.5),
                    new Threshold(400, 0.25),
                    new Threshold(500, 0.0)
                )
            )),
            "WEIGHTED_AVG"
        );
    }
    
    /**
     * Health evaluation rule for a single metric.
     */
    public record HealthRule(
        String metricName,
        String percentile,
        int windowSeconds,
        double weight,
        boolean isDerived,
        List<Threshold> thresholds
    ) {
        public HealthRule {
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
            if (thresholds == null || thresholds.isEmpty()) {
                thresholds = List.of(
                    new Threshold(100, 1.0),
                    new Threshold(500, 0.0)
                );
            }
        }
        
        /**
         * Convenience constructor for non-derived metrics.
         */
        public HealthRule(String metricName, String percentile, int windowSeconds, 
                          double weight, List<Threshold> thresholds) {
            this(metricName, percentile, windowSeconds, weight, false, thresholds);
        }
        
        /**
         * Get window as Duration.
         */
        public Duration window() {
            return Duration.ofSeconds(windowSeconds);
        }
        
        /**
         * Create a rule for a derived metric.
         */
        public static HealthRule derived(String metricName, double weight, List<Threshold> thresholds) {
            return new HealthRule(metricName, null, 0, weight, true, thresholds);
        }
    }
    
    /**
     * Single threshold point for interpolation.
     */
    public record Threshold(
        double value,
        double healthScore
    ) {
        public Threshold {
            if (healthScore < 0 || healthScore > 1) {
                throw new IllegalArgumentException("Health score must be 0.0 - 1.0");
            }
        }
    }
    
    /**
     * Aggregation strategies for multiple health rules.
     */
    public enum Aggregation {
        WEIGHTED_AVG,   // Weighted average of all rule scores
        WEIGHTED_MIN,   // Minimum score (most conservative)
        WEIGHTED_MAX,   // Maximum score (most optimistic)
        FIRST_MATCH     // Use first rule that matches
    }
}
