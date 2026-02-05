package io.github.lifecache.metrics;

/**
 * Aggregation types for metrics.
 * 
 * <p>For GAUGE metrics (point-in-time values like latency):</p>
 * <ul>
 *   <li>P50, P90, P95, P99 - percentiles</li>
 *   <li>AVG - average</li>
 * </ul>
 * 
 * <p>For COUNTER metrics (cumulative values like request count):</p>
 * <ul>
 *   <li>SUM - total sum in window</li>
 *   <li>RATE - per-second rate</li>
 *   <li>COUNT - number of samples</li>
 * </ul>
 */
public enum Aggregation {
    // Gauge aggregations (percentiles)
    P50(0.50, MetricType.GAUGE),
    P90(0.90, MetricType.GAUGE),
    P95(0.95, MetricType.GAUGE),
    P99(0.99, MetricType.GAUGE),
    AVG(-1, MetricType.GAUGE),
    
    // Counter aggregations
    SUM(-2, MetricType.COUNTER),
    RATE(-3, MetricType.COUNTER),
    COUNT(-4, MetricType.COUNTER);
    
    private final double value;
    private final MetricType metricType;
    
    Aggregation(double value, MetricType metricType) {
        this.value = value;
        this.metricType = metricType;
    }
    
    /**
     * Get percentile value (0.0 - 1.0).
     * Returns negative for non-percentile aggregations.
     */
    public double value() {
        return value;
    }
    
    /**
     * Check if this is a real percentile (not AVG/SUM/RATE/COUNT).
     */
    public boolean isPercentile() {
        return value >= 0;
    }
    
    /**
     * Check if this aggregation is for gauge metrics.
     */
    public boolean isGauge() {
        return metricType == MetricType.GAUGE;
    }
    
    /**
     * Check if this aggregation is for counter metrics.
     */
    public boolean isCounter() {
        return metricType == MetricType.COUNTER;
    }
    
    /**
     * Metric type categories.
     */
    public enum MetricType {
        GAUGE,   // Point-in-time measurements (latency, error_rate)
        COUNTER  // Cumulative measurements (request_count, bytes_sent)
    }
}
