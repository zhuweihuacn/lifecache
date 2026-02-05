package io.github.lifecache.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * JSON-serializable Metrics Configuration.
 * 
 * <p>Example JSON:</p>
 * <pre>{@code
 * {
 *   "metrics": [
 *     {
 *       "name": "grpc_latency",
 *       "bucketSeconds": 10,
 *       "maxBuckets": 6,
 *       "percentiles": ["P50", "P95", "P99"],
 *       "dropNegative": true,
 *       "maxValue": 10000
 *     },
 *     {
 *       "name": "inference_latency",
 *       "bucketSeconds": 10,
 *       "maxBuckets": 12,
 *       "percentiles": ["P95"],
 *       "dropNegative": true
 *     },
 *     {
 *       "name": "error_rate",
 *       "bucketSeconds": 10,
 *       "maxBuckets": 6,
 *       "percentiles": ["AVG"]
 *     }
 *   ],
 *   "bucketSeconds": 10,
 *   "maxBuckets": 6
 * }
 * }</pre>
 */
public record MetricsConfig(
    List<MetricDefinition> metrics,
    int bucketSeconds,
    int maxBuckets
) {
    
    public MetricsConfig {
        if (metrics == null) {
            metrics = List.of();
        }
        if (bucketSeconds <= 0) {
            bucketSeconds = 10;
        }
        if (maxBuckets <= 0) {
            maxBuckets = 6;
        }
    }
    
    /**
     * Total window duration = bucketSeconds × maxBuckets
     */
    public Duration window() {
        return Duration.ofSeconds((long) bucketSeconds * maxBuckets);
    }
    
    /**
     * Create default config for latency monitoring.
     */
    public static MetricsConfig defaultLatencyConfig() {
        return new MetricsConfig(
            List.of(new MetricDefinition(
                "latency",
                10,
                6,
                List.of("P50", "P95", "P99", "AVG"),
                true,
                60000.0,
                Map.of()
            )),
            10,
            6
        );
    }
    
    /**
     * Single metric definition.
     */
    public record MetricDefinition(
        String name,
        int bucketSeconds,
        int maxBuckets,
        List<String> percentiles,
        boolean dropNegative,
        Double maxValue,
        Map<String, Object> metadata
    ) {
        public MetricDefinition {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Metric name is required");
            }
            if (bucketSeconds <= 0) {
                bucketSeconds = 10;
            }
            if (maxBuckets <= 0) {
                maxBuckets = 6;
            }
            if (percentiles == null) {
                percentiles = List.of("P95");
            }
            if (metadata == null) {
                metadata = Map.of();
            }
        }
        
        /**
         * Simple metric definition with defaults.
         */
        public static MetricDefinition of(String name) {
            return new MetricDefinition(name, 10, 6, List.of("P95"), true, null, Map.of());
        }
        
        /**
         * Total window duration = bucketSeconds × maxBuckets
         */
        public Duration window() {
            return Duration.ofSeconds((long) bucketSeconds * maxBuckets);
        }
    }
}
