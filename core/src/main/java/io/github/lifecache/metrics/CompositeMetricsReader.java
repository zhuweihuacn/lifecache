package io.github.lifecache.metrics;

import io.github.lifecache.config.AdaptiveQoSConfig.DerivedMetricConfig;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MetricsReader that supports both raw metrics and derived (computed) metrics.
 *
 * <p>Derived metrics are computed from expressions like:</p>
 * <ul>
 *   <li>{@code error_rate = error_count / (error_count + success_count)}</li>
 *   <li>{@code cache_hit_rate = cache_hits / (cache_hits + cache_misses)}</li>
 * </ul>
 *
 * <pre>{@code
 * CompositeMetricsReader reader = new CompositeMetricsReader(baseRegistry)
 *     .addDerived("error_rate", "error_count / (error_count + success_count)", 
 *                 Aggregation.SUM, Duration.ofMinutes(5));
 *
 * // Read raw metric
 * Double latency = reader.read("latency", Aggregation.P95, Duration.ofSeconds(30));
 *
 * // Read derived metric (uses configured aggregation and window)
 * Double errorRate = reader.readDerived("error_rate");
 * }</pre>
 */
public class CompositeMetricsReader implements MetricsReader {
    
    private final MetricsReader baseReader;
    private final Map<String, DerivedMetric> derivedMetrics = new ConcurrentHashMap<>();
    
    public CompositeMetricsReader(MetricsReader baseReader) {
        this.baseReader = baseReader;
    }
    
    // ============ Configuration ============
    
    /**
     * Add a derived metric.
     */
    public CompositeMetricsReader addDerived(String name, String formula, 
                                              Aggregation aggregation, Duration window) {
        derivedMetrics.put(name, DerivedMetric.builder()
            .name(name)
            .formula(formula)
            .aggregation(aggregation)
            .window(window)
            .build());
        return this;
    }
    
    /**
     * Add a derived metric from config.
     */
    public CompositeMetricsReader addDerived(String name, DerivedMetricConfig config) {
        derivedMetrics.put(name, DerivedMetric.builder()
            .name(name)
            .formula(config.formula())
            .aggregation(Aggregation.valueOf(config.aggregation()))
            .window(config.window())
            .build());
        return this;
    }
    
    /**
     * Add multiple derived metrics from config map.
     */
    public CompositeMetricsReader addDerivedMetrics(Map<String, DerivedMetricConfig> configs) {
        if (configs != null) {
            configs.forEach(this::addDerived);
        }
        return this;
    }
    
    // ============ MetricsReader ============
    
    @Override
    public Double read(String metricName, Aggregation aggregation, Duration window) {
        // Check if it's a derived metric
        DerivedMetric derived = derivedMetrics.get(metricName);
        if (derived != null) {
            // For derived metrics, use their configured aggregation/window
            return derived.compute(baseReader);
        }
        
        // Otherwise, delegate to base reader
        return baseReader.read(metricName, aggregation, window);
    }
    
    // ============ Derived Metric Access ============
    
    /**
     * Read a derived metric using its configured aggregation and window.
     *
     * @param name derived metric name
     * @return computed value, or null if not found or computation failed
     */
    public Double readDerived(String name) {
        DerivedMetric derived = derivedMetrics.get(name);
        if (derived == null) {
            return null;
        }
        return derived.compute(baseReader);
    }
    
    /**
     * Check if a derived metric is configured.
     */
    public boolean hasDerived(String name) {
        return derivedMetrics.containsKey(name);
    }
    
    /**
     * Get the underlying base reader.
     */
    public MetricsReader getBaseReader() {
        return baseReader;
    }
}
