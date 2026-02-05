package io.github.lifecache.metrics;

import io.github.lifecache.config.AdaptiveQoSConfig.ProcessorConfig;
import io.github.lifecache.config.AdaptiveQoSConfig.SignalStoreConfig;
import io.github.lifecache.metrics.SignalStore.MetricType;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * MetricsRegistry with per-signal configuration and routing.
 * 
 * <p>Architecture:</p>
 * <pre>
 *   record("latency", 100)              read(query)
 *           │                                 │
 *           ▼                                 ▼
 * ┌───────────────────────────────────────────────────────┐
 * │              RoutedMetricsRegistry                    │
 * │  ┌─────────────────────────────────────────────────┐ │
 * │  │  Pre-filter: dropNegative, min/max validation   │ │
 * │  └───────────────────┬─────────────────────────────┘ │
 * │                      ▼                               │
 * │  ┌─────────────────────────────────────────────────┐ │
 * │  │  SignalStore: latency (GAUGE)                   │ │
 * │  │  - Aggregations: P50, P95, P99, AVG             │ │
 * │  └─────────────────────────────────────────────────┘ │
 * │  ┌─────────────────────────────────────────────────┐ │
 * │  │  SignalStore: request_count (COUNTER)           │ │
 * │  │  - Aggregations: SUM, RATE, COUNT               │ │
 * │  └─────────────────────────────────────────────────┘ │
 * └───────────────────────────────────────────────────────┘
 * </pre>
 */
public class RoutedMetricsRegistry implements MetricsRegistry {
    
    private final Map<String, SignalStore> signalStores = new ConcurrentHashMap<>();
    private final Map<String, SignalStoreConfig> signalConfigs = new ConcurrentHashMap<>();
    private final Map<String, Predicate<Double>> dropFilters = new ConcurrentHashMap<>();
    private final Duration defaultWindow;
    
    public RoutedMetricsRegistry(Duration windowSize) {
        this.defaultWindow = windowSize;
    }
    
    /**
     * Get the default window duration.
     */
    public Duration getDefaultWindow() {
        return defaultWindow;
    }
    
    // ============ Signal Configuration ============
    
    /**
     * Pre-configure a signal with specific settings.
     * Must be called before recording to that signal.
     */
    public RoutedMetricsRegistry configureSignal(String name, SignalStoreConfig config) {
        signalConfigs.put(name, config);
        
        // Create the store
        MetricType type = config.isCounter() ? MetricType.COUNTER : MetricType.GAUGE;
        SignalStore store = new SignalStore(name, type, config.window());
        signalStores.put(name, store);
        
        // Create drop filter from processor config
        Predicate<Double> filter = buildDropFilter(config.processor());
        dropFilters.put(name, filter);
        
        return this;
    }
    
    /**
     * Configure a GAUGE signal with per-signal processor.
     */
    public RoutedMetricsRegistry configureGauge(String name, ProcessorConfig processor) {
        return configureSignal(name, SignalStoreConfig.gauge(name).withProcessor(processor));
    }
    
    /**
     * Configure a COUNTER signal with per-signal processor.
     */
    public RoutedMetricsRegistry configureCounter(String name, ProcessorConfig processor) {
        return configureSignal(name, SignalStoreConfig.counter(name).withProcessor(processor));
    }
    
    /**
     * Get or create a SignalStore with appropriate config.
     */
    public SignalStore getOrCreateStore(String name) {
        return signalStores.computeIfAbsent(name, n -> {
            SignalStoreConfig config = signalConfigs.get(n);
            if (config != null) {
                MetricType type = config.isCounter() ? MetricType.COUNTER : MetricType.GAUGE;
                return new SignalStore(n, type, config.window());
            }
            // Default: GAUGE with default window
            dropFilters.put(n, v -> v < 0); // Default: drop negative
            return new SignalStore(n, MetricType.GAUGE, defaultWindow);
        });
    }
    
    private Predicate<Double> buildDropFilter(ProcessorConfig proc) {
        Predicate<Double> filter = v -> false; // Default: don't drop
        
        if (proc.dropNegative()) {
            filter = filter.or(v -> v < 0);
        }
        if (proc.maxValue() != null) {
            double max = proc.maxValue();
            filter = filter.or(v -> v > max);
        }
        if (proc.minValue() != null) {
            double min = proc.minValue();
            filter = filter.or(v -> v < min);
        }
        
        return filter;
    }
    
    // ============ MetricsWriter ============
    
    @Override
    public void record(MetricSample sample) {
        // Ensure store and filter are initialized first
        SignalStore store = getOrCreateStore(sample.name());
        
        // Apply drop filter before storing
        Predicate<Double> filter = dropFilters.get(sample.name());
        if (filter != null && filter.test(sample.value())) {
            return; // Drop this sample
        }
        
        store.write(sample.value());
    }
    
    // ============ MetricsReader ============
    
    @Override
    public Double read(String metricName, Aggregation aggregation, Duration window) {
        SignalStore store = signalStores.get(metricName);
        if (store == null) {
            return null;
        }
        return store.read(aggregation, window);
    }
    
    // ============ MetricsRegistry ============
    
    @Override
    public void clear() {
        signalStores.values().forEach(SignalStore::clear);
    }
    
    @Override
    public int getSampleCount() {
        // Return total count across all stores
        return signalStores.values().stream()
            .mapToInt(SignalStore::getSampleCount)
            .sum();
    }
    
    /**
     * Get sample count for a specific signal.
     */
    public int getSampleCount(String signalName) {
        SignalStore store = signalStores.get(signalName);
        return store != null ? store.getSampleCount() : 0;
    }
}
