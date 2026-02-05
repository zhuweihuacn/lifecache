package io.github.lifecache.metrics;

/**
 * Write-only metrics interface (ISP - Interface Segregation).
 */
public interface MetricsWriter {
    
    /**
     * Record a metric sample.
     */
    void record(MetricSample sample);
}
