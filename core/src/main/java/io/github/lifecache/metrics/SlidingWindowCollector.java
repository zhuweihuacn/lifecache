package io.github.lifecache.metrics;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sliding time window metrics collector.
 * Keeps samples from the last N seconds for percentile calculation.
 * Thread-safe for concurrent recording and reading.
 * 
 * <p>Internally caches computed metrics for efficiency.</p>
 */
public class SlidingWindowCollector implements MetricsCollector {
    
    private final ConcurrentLinkedDeque<TimestampedSample> samples = new ConcurrentLinkedDeque<>();
    private final long windowMs;
    
    // Internal cache for computed metrics
    private volatile CachedMetrics cachedMetrics = CachedMetrics.EMPTY;
    private final AtomicLong lastCalculationMs = new AtomicLong(0);
    private static final long CACHE_VALIDITY_MS = 100;
    
    public SlidingWindowCollector() {
        this(Duration.ofSeconds(10));
    }
    
    public SlidingWindowCollector(Duration window) {
        this.windowMs = window.toMillis();
    }
    
    @Override
    public void record(double latencyMs) {
        long now = System.currentTimeMillis();
        samples.addLast(new TimestampedSample(now, latencyMs));
        pruneOldSamples(now);
    }
    
    @Override
    public Sample startSample() {
        return new LatencySample(this);
    }
    
    @Override
    public Double read(MetricType type) {
        ensureCacheValid();
        CachedMetrics metrics = cachedMetrics;
        
        if (metrics.sampleCount == 0) {
            return null;
        }
        
        return switch (type) {
            case P50 -> metrics.p50;
            case P95 -> metrics.p95;
            case P99 -> metrics.p99;
            case AVERAGE -> metrics.average;
            case SAMPLE_COUNT -> (double) metrics.sampleCount;
        };
    }
    
    @Override
    public void clear() {
        samples.clear();
        cachedMetrics = CachedMetrics.EMPTY;
        lastCalculationMs.set(0);
    }
    
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
            .mapToDouble(s -> s.latencyMs)
            .sorted()
            .toArray();
        
        if (values.length == 0) {
            cachedMetrics = CachedMetrics.EMPTY;
            return;
        }
        
        cachedMetrics = new CachedMetrics(
            getPercentile(values, 0.50),
            getPercentile(values, 0.95),
            getPercentile(values, 0.99),
            Arrays.stream(values).average().orElse(0),
            values.length
        );
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
    
    private record TimestampedSample(long timestampMs, double latencyMs) {}
    
    /** Internal cached metrics - implementation detail */
    private record CachedMetrics(double p50, double p95, double p99, double average, int sampleCount) {
        static final CachedMetrics EMPTY = new CachedMetrics(0, 0, 0, 0, 0);
    }
    
    private static class LatencySample implements Sample {
        private final MetricsCollector collector;
        private final long startNanos;
        private boolean closed = false;
        
        LatencySample(MetricsCollector collector) {
            this.collector = collector;
            this.startNanos = System.nanoTime();
        }
        
        @Override
        public void close() {
            if (!closed) {
                double latencyMs = (System.nanoTime() - startNanos) / 1_000_000.0;
                collector.record(latencyMs);
                closed = true;
            }
        }
    }
}
