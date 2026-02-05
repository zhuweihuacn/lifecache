package io.github.lifecache.metrics;

/**
 * Combined metrics registry - extends both reader and writer.
 * Thread-safe for concurrent recording and reading.
 * 
 * <p>Architecture:</p>
 * <pre>
 * MetricsWriter.record()       MetricsReader.read()
 *         │                           │
 *         ▼                           ▼
 * ┌─────────────────────────────────────────────┐
 * │              MetricsRegistry                │
 * │  ┌───────────────────────────────────────┐  │
 * │  │ MetricsBackend (SignalStore per name) │  │
 * │  └───────────────────────────────────────┘  │
 * └─────────────────────────────────────────────┘
 * </pre>
 */
public interface MetricsRegistry extends MetricsWriter, MetricsReader {
    
    /**
     * Clear all recorded metrics.
     */
    void clear();
    
    /**
     * Get sample count in current window.
     */
    int getSampleCount();
}
