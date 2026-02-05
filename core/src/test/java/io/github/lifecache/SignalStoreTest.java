package io.github.lifecache;

import io.github.lifecache.config.AdaptiveQoSConfig.*;
import io.github.lifecache.metrics.*;
import io.github.lifecache.metrics.SignalStore.MetricType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the SignalStore architecture:
 * - SignalStore: single signal storage
 * - RoutedMetricsRegistry: routes to per-signal SignalStores with pre-filtering
 * - GAUGE vs COUNTER metric types
 */
@DisplayName("SignalStore Architecture Tests")
class SignalStoreTest {
    
    private static final Duration WINDOW = Duration.ofSeconds(60);
    
    // ============================================
    // 1. SignalStore Tests - GAUGE Metrics
    // ============================================
    
    @Test
    @DisplayName("SignalStore GAUGE - basic write and read")
    void signalStore_BasicWriteAndRead() {
        SignalStore store = new SignalStore("latency", MetricType.GAUGE, WINDOW);
        
        store.write(100);
        store.write(200);
        store.write(300);
        
        assertEquals("latency", store.getSignalName());
        assertEquals(MetricType.GAUGE, store.getMetricType());
        assertEquals(3, store.getSampleCount());
        
        Double p50 = store.read(Aggregation.P50);
        assertNotNull(p50);
        assertEquals(200.0, p50, 0.1);
    }
    
    @Test
    @DisplayName("SignalStore GAUGE - aggregation calculations")
    void signalStore_AggregationCalculations() {
        SignalStore store = new SignalStore("latency", MetricType.GAUGE, WINDOW);
        
        // Write 100 samples: 1, 2, 3, ..., 100
        for (int i = 1; i <= 100; i++) {
            store.write(i);
        }
        
        assertEquals(50.5, store.read(Aggregation.P50), 1.0);
        assertEquals(90.1, store.read(Aggregation.P90), 1.0);
        assertEquals(95.05, store.read(Aggregation.P95), 1.0);
        assertEquals(99.01, store.read(Aggregation.P99), 1.0);
        assertEquals(50.5, store.read(Aggregation.AVG), 0.1);
    }
    
    @Test
    @DisplayName("SignalStore - returns null when empty")
    void signalStore_ReturnsNullWhenEmpty() {
        SignalStore store = new SignalStore("latency", MetricType.GAUGE, WINDOW);
        assertNull(store.read(Aggregation.P95));
        assertEquals(0, store.getSampleCount());
    }
    
    @Test
    @DisplayName("SignalStore - clear removes all samples")
    void signalStore_Clear() {
        SignalStore store = new SignalStore("latency", MetricType.GAUGE, WINDOW);
        store.write(100);
        store.write(200);
        
        assertEquals(2, store.getSampleCount());
        
        store.clear();
        
        assertEquals(0, store.getSampleCount());
        assertNull(store.read(Aggregation.P95));
    }
    
    // ============================================
    // 2. SignalStore Tests - COUNTER Metrics
    // ============================================
    
    @Test
    @DisplayName("SignalStore COUNTER - SUM and RATE")
    void signalStore_Counter() {
        SignalStore store = new SignalStore("request_count", MetricType.COUNTER, Duration.ofSeconds(60));
        
        // Write 10 requests
        for (int i = 0; i < 10; i++) {
            store.write(1);
        }
        
        assertEquals(MetricType.COUNTER, store.getMetricType());
        assertEquals(10, store.getSampleCount());
        
        // SUM should be 10
        Double sum = store.read(Aggregation.SUM);
        assertNotNull(sum);
        assertEquals(10.0, sum, 0.1);
        
        // RATE should be sum / window_seconds
        Double rate = store.read(Aggregation.RATE);
        assertNotNull(rate);
        assertTrue(rate > 0);
        
        // COUNT should be 10
        Double count = store.read(Aggregation.COUNT);
        assertNotNull(count);
        assertEquals(10.0, count, 0.1);
    }
    
    // ============================================
    // 3. RoutedMetricsRegistry Tests - Pre-filtering
    // ============================================
    
    @Test
    @DisplayName("RoutedMetricsRegistry - full pipeline write and read")
    void routedRegistry_FullPipeline() {
        RoutedMetricsRegistry registry = new RoutedMetricsRegistry(WINDOW);
        
        // Record various signals
        registry.record(MetricSample.of("grpc_latency", 50));
        registry.record(MetricSample.of("grpc_latency", 100));
        registry.record(MetricSample.of("grpc_latency", 150));
        registry.record(MetricSample.of("inference_lat", 200));
        
        // Read back
        Double grpcP95 = registry.read("grpc_latency", Aggregation.P95, WINDOW);
        assertNotNull(grpcP95);
        assertEquals(145.0, grpcP95, 10.0);
        
        Double inferenceP95 = registry.read("inference_lat", Aggregation.P95, WINDOW);
        assertNotNull(inferenceP95);
        assertEquals(200.0, inferenceP95, 0.1);
    }
    
    @Test
    @DisplayName("RoutedMetricsRegistry - configure GAUGE with pre-filtering")
    void routedRegistry_ConfigureGauge() {
        RoutedMetricsRegistry registry = new RoutedMetricsRegistry(WINDOW);
        
        // Configure latency with dropNegative and maxValue (filtering before store)
        registry.configureGauge("latency", new ProcessorConfig(true, 10000.0, null));
        
        registry.record(MetricSample.of("latency", 100));
        registry.record(MetricSample.of("latency", -50));    // Dropped (negative)
        registry.record(MetricSample.of("latency", 50000));  // Dropped (> maxValue)
        registry.record(MetricSample.of("latency", 200));
        
        assertEquals(2, registry.getSampleCount("latency"));
    }
    
    @Test
    @DisplayName("RoutedMetricsRegistry - configure COUNTER")
    void routedRegistry_ConfigureCounter() {
        RoutedMetricsRegistry registry = new RoutedMetricsRegistry(WINDOW);
        
        // Configure request_count as COUNTER
        registry.configureCounter("request_count", ProcessorConfig.forCounter());
        
        // Record requests
        for (int i = 0; i < 10; i++) {
            registry.record(MetricSample.of("request_count", 1));
        }
        
        // Query SUM
        Double sum = registry.read("request_count", Aggregation.SUM, WINDOW);
        assertNotNull(sum);
        assertEquals(10.0, sum, 0.1);
        
        // Query RATE
        Double rate = registry.read("request_count", Aggregation.RATE, WINDOW);
        assertNotNull(rate);
        assertTrue(rate > 0);
    }
    
    @Test
    @DisplayName("RoutedMetricsRegistry - default drops negative")
    void routedRegistry_DefaultDropsNegative() {
        RoutedMetricsRegistry registry = new RoutedMetricsRegistry(WINDOW);
        
        // Default signal should drop negative
        registry.record(MetricSample.of("latency", -100));
        assertNull(registry.read("latency", Aggregation.P95, WINDOW));
        
        // Normal values should pass
        registry.record(MetricSample.of("latency", 100));
        assertNotNull(registry.read("latency", Aggregation.P95, WINDOW));
    }
    
    @Test
    @DisplayName("RoutedMetricsRegistry - clear clears all SignalStores")
    void routedRegistry_Clear() {
        RoutedMetricsRegistry registry = new RoutedMetricsRegistry(WINDOW);
        
        registry.record(MetricSample.of("latency", 100));
        registry.record(MetricSample.of("error_rate", 0.01));
        
        registry.clear();
        
        // SignalStores still exist but are empty
        assertNull(registry.read("latency", Aggregation.P95, WINDOW));
        assertNull(registry.read("error_rate", Aggregation.AVG, WINDOW));
    }
    
    // ============================================
    // 4. Query API Tests
    // ============================================
    
    @Test
    @DisplayName("Read with Aggregation - P95")
    void readWithAggregation_P95() {
        RoutedMetricsRegistry registry = new RoutedMetricsRegistry(WINDOW);
        
        for (int i = 1; i <= 100; i++) {
            registry.record(MetricSample.of("latency", i));
        }
        
        // Read P95
        Double p95 = registry.read("latency", Aggregation.P95, WINDOW);
        assertNotNull(p95);
        assertEquals(95.05, p95, 1.0);
    }
    
    @Test
    @DisplayName("Read with Aggregation - P99")
    void readWithAggregation_P99() {
        Duration longWindow = Duration.ofMinutes(5);
        RoutedMetricsRegistry registry = new RoutedMetricsRegistry(longWindow);
        
        for (int i = 1; i <= 100; i++) {
            registry.record(MetricSample.of("latency", i));
        }
        
        // Read P99
        Double p99 = registry.read("latency", Aggregation.P99, longWindow);
        assertNotNull(p99);
        assertEquals(99.01, p99, 1.0);
    }
    
    @Test
    @DisplayName("Read with Aggregation - AVG")
    void readWithAggregation_Avg() {
        RoutedMetricsRegistry registry = new RoutedMetricsRegistry(WINDOW);
        
        registry.configureGauge("error_rate", ProcessorConfig.forRate());
        
        registry.record(MetricSample.of("error_rate", 0.01));
        registry.record(MetricSample.of("error_rate", 0.02));
        registry.record(MetricSample.of("error_rate", 0.03));
        
        // Read AVG
        Double avg = registry.read("error_rate", Aggregation.AVG, WINDOW);
        assertNotNull(avg);
        assertEquals(0.02, avg, 0.001);
    }
    
    // ============================================
    // 5. Integration Tests
    // ============================================
    
    @Test
    @DisplayName("Integration - multiple signals with different types")
    void integration_MultipleSignalsWithTypes() {
        RoutedMetricsRegistry registry = new RoutedMetricsRegistry(WINDOW);
        
        // Configure different signal types
        registry.configureSignal("grpc_latency", SignalStoreConfig.latency());
        registry.configureCounter("request_count", ProcessorConfig.forCounter());
        registry.configureGauge("error_rate", ProcessorConfig.forRate());
        
        // Simulate real-world scenario
        for (int i = 0; i < 100; i++) {
            // Latency: 50-200ms
            registry.record(MetricSample.of("grpc_latency", 50 + Math.random() * 150));
            
            // Request count: 1 per iteration
            registry.record(MetricSample.of("request_count", 1));
            
            // Error rate: 0-10%
            registry.record(MetricSample.of("error_rate", Math.random() * 0.1));
        }
        
        // Verify GAUGE metrics
        Double grpcP95 = registry.read("grpc_latency", Aggregation.P95, WINDOW);
        assertNotNull(grpcP95);
        assertTrue(grpcP95 >= 50 && grpcP95 <= 200);
        
        // Verify COUNTER metrics
        Double requestSum = registry.read("request_count", Aggregation.SUM, WINDOW);
        assertNotNull(requestSum);
        assertEquals(100.0, requestSum, 0.1);
        
        // Verify error_rate (GAUGE with rate config)
        Double errorAvg = registry.read("error_rate", Aggregation.AVG, WINDOW);
        assertNotNull(errorAvg);
        assertTrue(errorAvg >= 0 && errorAvg <= 0.1);
    }
}
