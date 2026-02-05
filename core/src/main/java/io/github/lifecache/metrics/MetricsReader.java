package io.github.lifecache.metrics;

import java.time.Duration;

/**
 * Read-only metrics interface (ISP - Interface Segregation).
 *
 * <p>Both aggregation type and time window are specified by caller,
 * allowing runtime tuning for different use cases:</p>
 *
 * <pre>{@code
 * // Fast-changing metric: short window
 * Double latencyP95 = reader.read("latency", Aggregation.P95, Duration.ofSeconds(30));
 *
 * // Slow-changing metric: longer window for stability
 * Double errorRate = reader.read("error_rate", Aggregation.AVG, Duration.ofMinutes(5));
 * }</pre>
 */
public interface MetricsReader {
    
    /**
     * Read aggregated metric value over a time window.
     *
     * @param metricName metric name
     * @param aggregation aggregation type (P50, P95, AVG, etc.)
     * @param window time window to aggregate over
     * @return aggregated value, or null if no samples available
     */
    Double read(String metricName, Aggregation aggregation, Duration window);
}
