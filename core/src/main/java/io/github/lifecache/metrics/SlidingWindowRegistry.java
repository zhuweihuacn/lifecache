package io.github.lifecache.metrics;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Sliding time window metrics registry.
 * Keeps samples and calculates aggregations over caller-specified windows.
 * Thread-safe for concurrent recording and reading.
 * 
 * <p>Supports multiple named metrics (e.g. "latency", "error_rate").</p>
 */
public class SlidingWindowRegistry implements MetricsRegistry {
    
    // Per-metric sample storage
    private final Map<String, ConcurrentLinkedDeque<TimestampedSample>> metricSamples = new ConcurrentHashMap<>();
    
    // Max retention - samples older than this are pruned
    private final long maxRetentionMs;
    
    public SlidingWindowRegistry() {
        this(Duration.ofMinutes(10)); // Default: keep 10 minutes of data
    }
    
    public SlidingWindowRegistry(Duration maxRetention) {
        this.maxRetentionMs = maxRetention.toMillis();
    }
    
    // ============ MetricsWriter ============
    
    @Override
    public void record(MetricSample sample) {
        long now = System.currentTimeMillis();
        ConcurrentLinkedDeque<TimestampedSample> samples = metricSamples
            .computeIfAbsent(sample.name(), k -> new ConcurrentLinkedDeque<>());
        samples.addLast(new TimestampedSample(now, sample.value()));
        pruneOldSamples(samples, now);
    }
    
    // ============ MetricsReader ============
    
    @Override
    public Double read(String metricName, Aggregation aggregation, Duration window) {
        ConcurrentLinkedDeque<TimestampedSample> samples = metricSamples.get(metricName);
        if (samples == null || samples.isEmpty()) {
            return null;
        }
        
        long now = System.currentTimeMillis();
        long cutoff = now - window.toMillis();
        
        double[] values = samples.stream()
            .filter(s -> s.timestampMs >= cutoff)
            .mapToDouble(s -> s.value)
            .sorted()
            .toArray();
        
        if (values.length == 0) {
            return null;
        }
        
        return switch (aggregation) {
            case P50 -> getPercentile(values, 0.50);
            case P90 -> getPercentile(values, 0.90);
            case P95 -> getPercentile(values, 0.95);
            case P99 -> getPercentile(values, 0.99);
            case AVG -> Arrays.stream(values).average().orElse(0);
            case SUM -> Arrays.stream(values).sum();
            case COUNT -> (double) values.length;
            case RATE -> Arrays.stream(values).sum() / (window.toMillis() / 1000.0);
        };
    }
    
    // ============ MetricsRegistry ============
    
    @Override
    public void clear() {
        metricSamples.clear();
    }
    
    @Override
    public int getSampleCount() {
        return metricSamples.values().stream()
            .mapToInt(ConcurrentLinkedDeque::size)
            .sum();
    }
    
    /**
     * Get sample count for a specific metric within a window.
     */
    public int getSampleCount(String metricName, Duration window) {
        ConcurrentLinkedDeque<TimestampedSample> samples = metricSamples.get(metricName);
        if (samples == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        long cutoff = now - window.toMillis();
        return (int) samples.stream().filter(s -> s.timestampMs >= cutoff).count();
    }
    
    // ============ Internal Methods ============
    
    private void pruneOldSamples(ConcurrentLinkedDeque<TimestampedSample> samples, long now) {
        long cutoff = now - maxRetentionMs;
        while (!samples.isEmpty()) {
            TimestampedSample oldest = samples.peekFirst();
            if (oldest != null && oldest.timestampMs < cutoff) {
                samples.pollFirst();
            } else {
                break;
            }
        }
    }
    
    private static double getPercentile(double[] sortedData, double percentile) {
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
    
    private record TimestampedSample(long timestampMs, double value) {}
}
