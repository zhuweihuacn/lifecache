package io.github.lifecache.metrics;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single metric SignalStore - stores samples for one signal/metric name.
 * Thread-safe sliding window storage with aggregation calculation.
 * 
 * <p>Supports two metric types:</p>
 * <ul>
 *   <li><b>GAUGE</b>: Point-in-time values (latency, error_rate) - use P50, P95, P99, AVG</li>
 *   <li><b>COUNTER</b>: Cumulative values (request_count, bytes) - use SUM, RATE, COUNT</li>
 * </ul>
 */
public class SignalStore {
    
    private final String signalName;
    private final MetricType metricType;
    private final ConcurrentLinkedDeque<TimestampedSample> samples = new ConcurrentLinkedDeque<>();
    private final long windowMs;
    
    // Cache for computed metrics
    private volatile CachedMetrics cachedMetrics = CachedMetrics.EMPTY;
    private final AtomicLong lastCalculationMs = new AtomicLong(0);
    private static final long CACHE_VALIDITY_MS = 100;
    
    public SignalStore(String signalName, MetricType metricType, Duration window) {
        this.signalName = signalName;
        this.metricType = metricType;
        this.windowMs = window.toMillis();
    }
    
    /**
     * Get the signal name this SignalStore manages.
     */
    public String getSignalName() {
        return signalName;
    }
    
    /**
     * Get the metric type (GAUGE or COUNTER).
     */
    public MetricType getMetricType() {
        return metricType;
    }
    
    // ============ Write ============
    
    /**
     * Write a sample to this SignalStore.
     */
    public void write(double value) {
        write(value, System.currentTimeMillis());
    }
    
    /**
     * Write a sample with specific timestamp.
     */
    public void write(double value, long timestampMs) {
        samples.addLast(new TimestampedSample(timestampMs, value));
        pruneOldSamples(timestampMs);
        // Invalidate cache so next read recalculates
        lastCalculationMs.set(0);
    }
    
    // ============ Read ============
    
    /**
     * Read aggregated value over a specific time window.
     *
     * @param aggregation aggregation type (P50, P95, SUM, RATE, etc.)
     * @param window time window to aggregate over
     * @return aggregated value, or null if no samples
     */
    public Double read(Aggregation aggregation, Duration window) {
        long now = System.currentTimeMillis();
        long cutoff = now - window.toMillis();
        
        double[] values = samples.stream()
            .filter(s -> s.timestampMs >= cutoff)
            .mapToDouble(s -> s.value)
            .toArray();
        
        if (values.length == 0) {
            return null;
        }
        
        // Sort for percentile calculation
        double[] sorted = Arrays.copyOf(values, values.length);
        Arrays.sort(sorted);
        
        return switch (aggregation) {
            // Gauge aggregations
            case P50 -> computePercentile(sorted, 0.50);
            case P90 -> computePercentile(sorted, 0.90);
            case P95 -> computePercentile(sorted, 0.95);
            case P99 -> computePercentile(sorted, 0.99);
            case AVG -> Arrays.stream(values).average().orElse(0);
            // Counter aggregations
            case SUM -> Arrays.stream(values).sum();
            case RATE -> Arrays.stream(values).sum() / (window.toMillis() / 1000.0);
            case COUNT -> (double) values.length;
        };
    }
    
    /**
     * Read aggregated value using the configured window.
     *
     * @param aggregation aggregation type (P50, P95, SUM, RATE, etc.)
     * @return aggregated value, or null if no samples
     */
    public Double read(Aggregation aggregation) {
        return read(aggregation, Duration.ofMillis(windowMs));
    }
    
    /**
     * Get sample count in current window.
     */
    public int getSampleCount() {
        ensureCacheValid();
        return cachedMetrics.sampleCount;
    }
    
    /**
     * Clear all samples.
     */
    public void clear() {
        samples.clear();
        cachedMetrics = CachedMetrics.EMPTY;
        lastCalculationMs.set(0);
    }
    
    // ============ Internal Methods ============
    
    private void pruneOldSamples(long now) {
        long cutoff = now - windowMs;
        while (!samples.isEmpty()) {
            TimestampedSample oldest = samples.peekFirst();
            if (oldest != null && oldest.timestampMs < cutoff) {
                samples.pollFirst();
            } else {
                break;
            }
        }
    }
    
    private void ensureCacheValid() {
        long now = System.currentTimeMillis();
        long lastCalc = lastCalculationMs.get();
        
        if (now - lastCalc < CACHE_VALIDITY_MS) {
            return;
        }
        
        if (lastCalculationMs.compareAndSet(lastCalc, now)) {
            calculateMetrics(now);
        }
    }
    
    private void calculateMetrics(long now) {
        long cutoff = now - windowMs;
        
        double[] values = samples.stream()
            .filter(s -> s.timestampMs >= cutoff)
            .mapToDouble(s -> s.value)
            .toArray();
        
        if (values.length == 0) {
            cachedMetrics = CachedMetrics.EMPTY;
            return;
        }
        
        // Sort for percentile calculation
        double[] sorted = Arrays.copyOf(values, values.length);
        Arrays.sort(sorted);
        
        // Calculate sum for counter metrics
        double sum = Arrays.stream(values).sum();
        double windowSeconds = windowMs / 1000.0;
        double rate = sum / windowSeconds;
        
        cachedMetrics = new CachedMetrics(
            computePercentile(sorted, 0.50),
            computePercentile(sorted, 0.90),
            computePercentile(sorted, 0.95),
            computePercentile(sorted, 0.99),
            Arrays.stream(values).average().orElse(0),
            sum,
            rate,
            values.length
        );
    }
    
    private static double computePercentile(double[] sortedData, double percentile) {
        if (sortedData.length == 0) return 0;
        if (sortedData.length == 1) return sortedData[0];
        
        double index = (sortedData.length - 1) * percentile;
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        
        if (lower == upper) {
            return sortedData[lower];
        }
        
        return sortedData[lower] + (sortedData[upper] - sortedData[lower]) * (index - lower);
    }
    
    // ============ Inner Classes ============
    
    /**
     * Metric type: GAUGE (point-in-time) or COUNTER (cumulative).
     */
    public enum MetricType {
        /**
         * Point-in-time measurements (latency, error_rate).
         * Use P50, P95, P99, AVG.
         */
        GAUGE,
        
        /**
         * Cumulative measurements (request_count, bytes_sent).
         * Use SUM, RATE, COUNT.
         */
        COUNTER
    }
    
    private record TimestampedSample(long timestampMs, double value) {}
    
    private record CachedMetrics(
        double p50, double p90, double p95, double p99, 
        double average, double sum, double rate, int sampleCount
    ) {
        static final CachedMetrics EMPTY = new CachedMetrics(0, 0, 0, 0, 0, 0, 0, 0);
    }
}
