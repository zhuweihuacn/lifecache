package io.github.lifecache;

import io.github.lifecache.config.AdaptiveQoSConfig;
import io.github.lifecache.config.ConfigLoader;
import io.github.lifecache.metrics.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DerivedMetric and CompositeMetricsReader.
 */
@DisplayName("Derived Metrics Tests")
class DerivedMetricTest {
    
    private static final Duration WINDOW = Duration.ofMinutes(5);
    
    @Test
    @DisplayName("DerivedMetric - error rate calculation")
    void testErrorRateCalculation() {
        SlidingWindowRegistry registry = new SlidingWindowRegistry();
        
        // Record 10 errors and 90 successes (10% error rate)
        for (int i = 0; i < 10; i++) {
            registry.record(MetricSample.of("error_count", 1));
        }
        for (int i = 0; i < 90; i++) {
            registry.record(MetricSample.of("success_count", 1));
        }
        
        DerivedMetric errorRate = DerivedMetric.builder()
            .name("error_rate")
            .formula("error_count / (error_count + success_count)")
            .aggregation(Aggregation.SUM)
            .window(WINDOW)
            .build();
        
        Double rate = errorRate.compute(registry);
        assertNotNull(rate);
        assertEquals(0.1, rate, 0.001); // 10 / 100 = 0.1
    }
    
    @Test
    @DisplayName("DerivedMetric - cache hit rate calculation")
    void testCacheHitRateCalculation() {
        SlidingWindowRegistry registry = new SlidingWindowRegistry();
        
        // Record 80 hits and 20 misses (80% hit rate)
        for (int i = 0; i < 80; i++) {
            registry.record(MetricSample.of("cache_hits", 1));
        }
        for (int i = 0; i < 20; i++) {
            registry.record(MetricSample.of("cache_misses", 1));
        }
        
        DerivedMetric hitRate = DerivedMetric.builder()
            .name("cache_hit_rate")
            .formula("cache_hits / (cache_hits + cache_misses)")
            .aggregation(Aggregation.SUM)
            .window(WINDOW)
            .build();
        
        Double rate = hitRate.compute(registry);
        assertNotNull(rate);
        assertEquals(0.8, rate, 0.001); // 80 / 100 = 0.8
    }
    
    @Test
    @DisplayName("DerivedMetric - complex expression")
    void testComplexExpression() {
        SlidingWindowRegistry registry = new SlidingWindowRegistry();
        
        registry.record(MetricSample.of("bytes_sent", 1000));
        registry.record(MetricSample.of("bytes_sent", 2000));
        registry.record(MetricSample.of("bytes_sent", 3000));
        registry.record(MetricSample.of("request_count", 6));
        
        DerivedMetric avgBytesPerRequest = DerivedMetric.builder()
            .name("avg_bytes_per_request")
            .formula("bytes_sent / request_count")
            .aggregation(Aggregation.SUM)
            .window(WINDOW)
            .build();
        
        Double avg = avgBytesPerRequest.compute(registry);
        assertNotNull(avg);
        assertEquals(1000.0, avg, 0.1); // 6000 / 6 = 1000
    }
    
    @Test
    @DisplayName("DerivedMetric - returns null when metric unavailable")
    void testReturnsNullWhenUnavailable() {
        SlidingWindowRegistry registry = new SlidingWindowRegistry();
        
        // Only record error_count, not success_count
        registry.record(MetricSample.of("error_count", 10));
        
        DerivedMetric errorRate = DerivedMetric.builder()
            .name("error_rate")
            .formula("error_count / (error_count + success_count)")
            .aggregation(Aggregation.SUM)
            .window(WINDOW)
            .build();
        
        Double rate = errorRate.compute(registry);
        assertNull(rate); // success_count is null
    }
    
    @Test
    @DisplayName("DerivedMetric - handles division by zero")
    void testDivisionByZero() {
        SlidingWindowRegistry registry = new SlidingWindowRegistry();
        
        // Both counters are 0
        registry.record(MetricSample.of("numerator", 0));
        registry.record(MetricSample.of("denominator", 0));
        
        DerivedMetric ratio = DerivedMetric.builder()
            .name("ratio")
            .formula("numerator / denominator")
            .aggregation(Aggregation.SUM)
            .window(WINDOW)
            .build();
        
        Double result = ratio.compute(registry);
        assertNull(result); // Division by zero returns null
    }
    
    @Test
    @DisplayName("CompositeMetricsReader - read derived metric")
    void testCompositeMetricsReader() {
        SlidingWindowRegistry registry = new SlidingWindowRegistry();
        
        // Record data
        for (int i = 0; i < 5; i++) {
            registry.record(MetricSample.of("error_count", 1));
        }
        for (int i = 0; i < 95; i++) {
            registry.record(MetricSample.of("success_count", 1));
        }
        
        CompositeMetricsReader reader = new CompositeMetricsReader(registry)
            .addDerived("error_rate", "error_count / (error_count + success_count)", 
                        Aggregation.SUM, WINDOW);
        
        // Read derived metric
        Double errorRate = reader.readDerived("error_rate");
        assertNotNull(errorRate);
        assertEquals(0.05, errorRate, 0.001); // 5 / 100 = 0.05
        
        // Can also read via read() method
        Double errorRate2 = reader.read("error_rate", Aggregation.SUM, WINDOW);
        assertNotNull(errorRate2);
        assertEquals(0.05, errorRate2, 0.001);
    }
    
    @Test
    @DisplayName("CompositeMetricsReader - read raw metric")
    void testCompositeMetricsReaderRawMetric() {
        SlidingWindowRegistry registry = new SlidingWindowRegistry();
        
        registry.record(MetricSample.of("latency", 100));
        registry.record(MetricSample.of("latency", 200));
        
        CompositeMetricsReader reader = new CompositeMetricsReader(registry);
        
        // Read raw metric (not derived)
        Double avg = reader.read("latency", Aggregation.AVG, WINDOW);
        assertNotNull(avg);
        assertEquals(150.0, avg, 0.1);
    }
    
    @Test
    @DisplayName("Config - load derived metrics from JSON")
    void testLoadDerivedMetricsFromConfig() throws Exception {
        AdaptiveQoSConfig config = ConfigLoader.fromResource("adaptive-qos-config.json");
        
        assertNotNull(config.derivedMetrics());
        assertTrue(config.derivedMetrics().containsKey("error_rate"));
        assertTrue(config.derivedMetrics().containsKey("cache_hit_rate"));
        
        AdaptiveQoSConfig.DerivedMetricConfig errorRateConfig = config.derivedMetrics().get("error_rate");
        assertEquals("error_count / (error_count + success_count)", errorRateConfig.formula());
        assertEquals("SUM", errorRateConfig.aggregation());
        assertEquals(300, errorRateConfig.windowSeconds());
    }
    
    @Test
    @DisplayName("CompositeMetricsReader - add from config")
    void testAddFromConfig() throws Exception {
        AdaptiveQoSConfig config = ConfigLoader.fromResource("adaptive-qos-config.json");
        SlidingWindowRegistry registry = new SlidingWindowRegistry();
        
        // Record data
        for (int i = 0; i < 10; i++) {
            registry.record(MetricSample.of("error_count", 1));
            registry.record(MetricSample.of("success_count", 9));
        }
        
        CompositeMetricsReader reader = new CompositeMetricsReader(registry)
            .addDerivedMetrics(config.derivedMetrics());
        
        assertTrue(reader.hasDerived("error_rate"));
        assertTrue(reader.hasDerived("cache_hit_rate"));
        
        Double errorRate = reader.readDerived("error_rate");
        assertNotNull(errorRate);
        assertEquals(0.1, errorRate, 0.001); // 10 / 100 = 0.1
    }
    
    @Test
    @DisplayName("DerivedMetric - get referenced metrics")
    void testGetReferencedMetrics() {
        DerivedMetric errorRate = DerivedMetric.builder()
            .name("error_rate")
            .formula("error_count / (error_count + success_count)")
            .aggregation(Aggregation.SUM)
            .window(WINDOW)
            .build();
        
        var metrics = errorRate.getReferencedMetrics();
        assertEquals(2, metrics.size());
        assertTrue(metrics.contains("error_count"));
        assertTrue(metrics.contains("success_count"));
    }
    
    @Test
    @DisplayName("Expression parser - arithmetic operations")
    void testExpressionParser() {
        SlidingWindowRegistry registry = new SlidingWindowRegistry();
        
        registry.record(MetricSample.of("a", 10));
        registry.record(MetricSample.of("b", 5));
        registry.record(MetricSample.of("c", 2));
        
        // Test addition
        DerivedMetric sum = DerivedMetric.builder()
            .name("sum")
            .formula("a + b")
            .aggregation(Aggregation.SUM)
            .window(WINDOW)
            .build();
        assertEquals(15.0, sum.compute(registry), 0.001);
        
        // Test subtraction
        DerivedMetric diff = DerivedMetric.builder()
            .name("diff")
            .formula("a - b")
            .aggregation(Aggregation.SUM)
            .window(WINDOW)
            .build();
        assertEquals(5.0, diff.compute(registry), 0.001);
        
        // Test multiplication
        DerivedMetric product = DerivedMetric.builder()
            .name("product")
            .formula("a * b")
            .aggregation(Aggregation.SUM)
            .window(WINDOW)
            .build();
        assertEquals(50.0, product.compute(registry), 0.001);
        
        // Test complex expression with parentheses
        DerivedMetric complex = DerivedMetric.builder()
            .name("complex")
            .formula("(a + b) * c")
            .aggregation(Aggregation.SUM)
            .window(WINDOW)
            .build();
        assertEquals(30.0, complex.compute(registry), 0.001);
    }
}
