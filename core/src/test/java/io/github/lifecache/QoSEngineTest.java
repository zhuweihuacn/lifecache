package io.github.lifecache;

import io.github.lifecache.config.*;
import io.github.lifecache.config.QoSEvaluatorConfig.*;
import io.github.lifecache.config.QoSFunctionConfig.*;
import io.github.lifecache.engine.QoSEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("QoS Engine Tests - JSON Configuration")
class QoSEngineTest {
    
    @Test
    @DisplayName("Basic engine with default configs")
    void testBasicEngine() {
        QoSEngine engine = QoSEngine.builder().build();
        
        // Record some latencies
        for (int i = 0; i < 10; i++) {
            engine.record("latency", 50 + Math.random() * 50);  // 50-100ms
        }
        
        QoSOutput output = engine.evaluate();
        
        assertNotNull(output);
        assertTrue(output.healthScore() >= 0.8);  // Should be healthy
        assertEquals("HEALTHY", output.status());
        assertNotNull(output.getAllowStaleness());
    }
    
    @Test
    @DisplayName("Degraded system produces correct output")
    void testDegradedSystem() {
        QoSEngine engine = QoSEngine.builder().build();
        
        // Record high latencies (200-300ms)
        for (int i = 0; i < 10; i++) {
            engine.record("latency", 250);
        }
        
        QoSOutput output = engine.evaluate();
        
        assertTrue(output.healthScore() >= 0.4 && output.healthScore() <= 0.7);
        assertTrue(output.status().equals("DEGRADED") || output.status().equals("STRESSED"));
        
        // Soft TTL should be longer when degraded
        Duration ttl = output.getAllowStaleness();
        assertNotNull(ttl);
        assertTrue(ttl.toSeconds() > 60);  // Should be extended
    }
    
    @Test
    @DisplayName("Critical system produces correct output")
    void testCriticalSystem() {
        QoSEngine engine = QoSEngine.builder().build();
        
        // Record very high latencies (500ms+)
        for (int i = 0; i < 10; i++) {
            engine.record("latency", 600);
        }
        
        QoSOutput output = engine.evaluate();
        
        assertTrue(output.healthScore() <= 0.1);
        assertEquals("CRITICAL", output.status());
        
        // Soft TTL should be at max
        Duration ttl = output.getAllowStaleness();
        assertNotNull(ttl);
        assertTrue(ttl.toSeconds() >= 1500);  // Should be near max
        
        // Throttle rate should be high
        Double throttleRate = output.getDouble("throttleRate");
        assertNotNull(throttleRate);
        assertTrue(throttleRate > 0.5);
    }
    
    @Test
    @DisplayName("Custom metrics configuration")
    void testCustomMetricsConfig() {
        MetricsConfig metricsConfig = new MetricsConfig(
            List.of(
                new MetricsConfig.MetricDefinition(
                    "inference_latency",
                    10,   // bucketSeconds
                    12,   // maxBuckets (120 seconds total)
                    List.of("P95", "P99"),
                    true,
                    30000.0,
                    Map.of()
                )
            ),
            10,   // bucketSeconds
            12    // maxBuckets
        );
        
        QoSEvaluatorConfig evaluatorConfig = new QoSEvaluatorConfig(
            List.of(new HealthRule(
                "inference_latency",
                "P95",
                30,  // windowSeconds
                1.0,
                List.of(
                    new Threshold(200, 1.0),
                    new Threshold(500, 0.5),
                    new Threshold(1000, 0.0)
                )
            )),
            "WEIGHTED_AVG"
        );
        
        QoSEngine engine = QoSEngine.builder()
            .metricsConfig(metricsConfig)
            .evaluatorConfig(evaluatorConfig)
            .build();
        
        // Record inference latencies
        for (int i = 0; i < 10; i++) {
            engine.record("inference_latency", 300);
        }
        
        QoSOutput output = engine.evaluate();
        
        assertTrue(output.healthScore() >= 0.5 && output.healthScore() <= 0.9);
        assertTrue(output.metrics().containsKey("inference_latency_p95"));
    }
    
    @Test
    @DisplayName("Multiple health rules with weighted aggregation")
    void testMultipleHealthRules() {
        QoSEvaluatorConfig evaluatorConfig = new QoSEvaluatorConfig(
            List.of(
                new HealthRule(
                    "latency",
                    "P95",
                    30,  // windowSeconds
                    0.6,  // 60% weight
                    List.of(
                        new Threshold(100, 1.0),
                        new Threshold(500, 0.0)
                    )
                ),
                new HealthRule(
                    "error_rate",
                    "AVG",
                    60,  // windowSeconds
                    0.4,  // 40% weight
                    List.of(
                        new Threshold(0.01, 1.0),
                        new Threshold(0.1, 0.0)
                    )
                )
            ),
            "WEIGHTED_AVG"
        );
        
        QoSEngine engine = QoSEngine.builder()
            .evaluatorConfig(evaluatorConfig)
            .build();
        
        // Good latency but high error rate
        for (int i = 0; i < 10; i++) {
            engine.record("latency", 50);      // Very good: health = 1.0
            engine.record("error_rate", 0.08); // Bad: health ~= 0.2
        }
        
        QoSOutput output = engine.evaluate();
        
        // Expected: 0.6 * 1.0 + 0.4 * 0.2 = 0.68
        assertTrue(output.healthScore() >= 0.5 && output.healthScore() <= 0.8);
    }
    
    @Test
    @DisplayName("Custom QoS function outputs")
    void testCustomFunctionOutputs() {
        QoSFunctionConfig functionConfig = new QoSFunctionConfig(List.of(
            new OutputDefinition(
                "allowStaleness",
                OutputType.DURATION,
                FunctionType.LINEAR,
                Map.of("minValue", 60, "maxValue", 3600, "unit", "SECONDS")
            ),
            new OutputDefinition(
                "priority",
                OutputType.INTEGER,
                FunctionType.STEP,
                Map.of("steps", List.of(
                    Map.of("healthMin", 0.8, "value", 1),
                    Map.of("healthMin", 0.5, "value", 2),
                    Map.of("healthMin", 0.0, "value", 3)
                ))
            ),
            new OutputDefinition(
                "useCache",
                OutputType.BOOLEAN,
                FunctionType.THRESHOLD,
                Map.of("threshold", 0.3, "above", true, "below", false)
            )
        ));
        
        QoSEngine engine = QoSEngine.builder()
            .functionConfig(functionConfig)
            .build();
        
        // Healthy system
        for (int i = 0; i < 10; i++) {
            engine.record("latency", 50);
        }
        
        QoSOutput output = engine.evaluate();
        
        // Check priority (should be 1 for healthy)
        Integer priority = output.getInteger("priority");
        assertNotNull(priority);
        assertEquals(1, priority);
        
        // Check useCache (should be true when healthy)
        Boolean useCache = output.getBoolean("useCache");
        assertNotNull(useCache);
        assertTrue(useCache);
    }
    
    @Test
    @DisplayName("QoSOutput JSON structure validation")
    void testOutputJsonStructure() {
        QoSEngine engine = QoSEngine.builder().build();
        
        for (int i = 0; i < 10; i++) {
            engine.record("latency", 150);
        }
        
        QoSOutput output = engine.evaluate();
        
        // Validate structure
        assertNotNull(output.timestamp());
        assertTrue(output.healthScore() >= 0 && output.healthScore() <= 1);
        assertNotNull(output.status());
        assertNotNull(output.decisions());
        assertNotNull(output.metrics());
        assertNotNull(output.metadata());
        
        // Check decisions contain expected keys
        assertTrue(output.decisions().containsKey("allowStaleness"));
        assertTrue(output.decisions().containsKey("throttleRate"));
        
        // Check decision value structure
        QoSOutput.DecisionValue ttlValue = output.decisions().get("allowStaleness");
        assertNotNull(ttlValue);
        assertEquals(QoSFunctionConfig.OutputType.DURATION, ttlValue.type());
        assertNotNull(ttlValue.value());
        assertNotNull(ttlValue.unit());
        assertNotNull(ttlValue.formatted());
    }
    
    @Test
    @DisplayName("No data returns healthy status")
    void testNoDataReturnsHealthy() {
        QoSEngine engine = QoSEngine.builder().build();
        
        // No metrics recorded
        QoSOutput output = engine.evaluate();
        
        assertEquals(1.0, output.healthScore());
        assertEquals("HEALTHY", output.status());
    }
    
    @Test
    @DisplayName("Sigmoid function produces smooth transitions")
    void testSigmoidFunction() {
        QoSFunctionConfig functionConfig = new QoSFunctionConfig(List.of(
            new OutputDefinition(
                "smoothTTL",
                OutputType.DURATION,
                FunctionType.SIGMOID,
                Map.of("minValue", 30, "maxValue", 1800, "steepness", 10.0)
            )
        ));
        
        QoSEngine healthyEngine = QoSEngine.builder()
            .functionConfig(functionConfig)
            .build();
        
        QoSEngine degradedEngine = QoSEngine.builder()
            .functionConfig(functionConfig)
            .build();
        
        // Healthy system
        for (int i = 0; i < 10; i++) {
            healthyEngine.record("latency", 50);
        }
        
        // Degraded system
        for (int i = 0; i < 10; i++) {
            degradedEngine.record("latency", 300);
        }
        
        QoSOutput healthyOutput = healthyEngine.evaluate();
        QoSOutput degradedOutput = degradedEngine.evaluate();
        
        Duration healthyTTL = healthyOutput.getDuration("smoothTTL");
        Duration degradedTTL = degradedOutput.getDuration("smoothTTL");
        
        assertNotNull(healthyTTL);
        assertNotNull(degradedTTL);
        
        // Sigmoid should produce smooth transition
        assertTrue(degradedTTL.compareTo(healthyTTL) > 0);
    }
}
