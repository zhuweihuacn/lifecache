package io.github.lifecache.metrics;

import java.time.Instant;

/**
 * A single metric sample.
 *
 * @param name metric name (e.g. "latency", "error_rate", "queue_depth")
 * @param value metric value
 * @param timestamp sample timestamp
 */
public record MetricSample(
    String name,
    double value,
    Instant timestamp
) {
    
    public MetricSample {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Metric name cannot be null or blank");
        }
    }
    
    /**
     * Create sample with current timestamp.
     */
    public static MetricSample of(String name, double value) {
        return new MetricSample(name, value, Instant.now());
    }
}
