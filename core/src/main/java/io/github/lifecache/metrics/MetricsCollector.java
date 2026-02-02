package io.github.lifecache.metrics;

/**
 * Abstraction over the metrics backend used by Lifecache.
 * Thread-safe for concurrent recording and reading.
 * 
 * <p>Uses Config Read pattern - read individual metrics by type.</p>
 */
public interface MetricsCollector {
    
    /**
     * Record a latency value directly.
     * @param latencyMs latency in milliseconds
     */
    void record(double latencyMs);
    
    /**
     * Start a latency sample that will automatically call {@link #record(double)} on close.
     * 
     * <pre>{@code
     * try (MetricsCollector.Sample sample = collector.startSample()) {
     *     // do work...
     * } // latency automatically recorded
     * }</pre>
     */
    Sample startSample();
    
    /**
     * Read a specific metric value.
     * 
     * @param type the type of metric to read
     * @return the metric value, or null if no samples available
     */
    Double read(MetricType type);
    
    /**
     * Clear all recorded metrics.
     */
    void clear();
    
    // ============ Convenience Methods ============
    
    /**
     * Get P50 (median) latency
     */
    default double getP50() {
        Double value = read(MetricType.P50);
        return value != null ? value : 0.0;
    }
    
    /**
     * Get P95 latency
     */
    default double getP95() {
        Double value = read(MetricType.P95);
        return value != null ? value : 0.0;
    }
    
    /**
     * Get P99 latency
     */
    default double getP99() {
        Double value = read(MetricType.P99);
        return value != null ? value : 0.0;
    }
    
    /**
     * Get average latency
     */
    default double getAverage() {
        Double value = read(MetricType.AVERAGE);
        return value != null ? value : 0.0;
    }
    
    /**
     * Get sample count in current window
     */
    default int getSampleCount() {
        Double value = read(MetricType.SAMPLE_COUNT);
        return value != null ? value.intValue() : 0;
    }
    
    // ============ Nested Types ============
    
    /**
     * AutoCloseable sample for latency tracking.
     */
    interface Sample extends AutoCloseable {
        @Override
        void close();  // No exception declared
    }
}
