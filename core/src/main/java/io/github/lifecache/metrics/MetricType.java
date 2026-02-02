package io.github.lifecache.metrics;

/**
 * Types of metrics that can be read from a MetricsCollector.
 * Extensible for future metric types.
 */
public enum MetricType {
    /** 50th percentile (median) latency */
    P50,
    
    /** 95th percentile latency */
    P95,
    
    /** 99th percentile latency */
    P99,
    
    /** Average latency */
    AVERAGE,
    
    /** Number of samples in current window */
    SAMPLE_COUNT
}
