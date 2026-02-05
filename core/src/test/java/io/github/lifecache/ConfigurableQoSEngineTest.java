package io.github.lifecache;

import io.github.lifecache.config.*;
import io.github.lifecache.config.AdaptiveQoSConfig.*;
import io.github.lifecache.engine.ConfigurableQoSEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Configurable QoS Engine Tests - Full JSON Configuration")
class ConfigurableQoSEngineTest {
    
    @Test
    @DisplayName("Load from JSON string - complete config")
    void testFromJsonString() {
        String json = """
            {
              "registry": {
                "type": "ROUTED",
                "signalStores": [
                  {
                    "name": "latency",
                    "bucketSeconds": 10,
                    "maxBuckets": 6
                  }
                ],
                "processor": {
                  "dropNegative": true
                }
              },
              "evaluator": {
                "rules": [
                  {
                    "metricName": "latency",
                    "percentile": "P95",
                    "weight": 1.0,
                    "stepFunction": {
                      "thresholds": [
                        { "value": 100, "score": 1.0 },
                        { "value": 200, "score": 0.75 },
                        { "value": 300, "score": 0.5 },
                        { "value": 500, "score": 0.0 }
                      ],
                      "interpolation": "LINEAR"
                    }
                  }
                ],
                "aggregation": "WEIGHTED_AVG"
              },
              "decisions": {
                "allowStaleness": {
                  "type": "DURATION",
                  "steps": [
                    { "healthMin": 1.0, "value": 30 },
                    { "healthMin": 0.0, "value": 1800 }
                  ],
                  "interpolation": "LINEAR"
                },
                "throttleRate": {
                  "type": "DOUBLE",
                  "steps": [
                    { "healthMin": 0.5, "value": 0.0 },
                    { "healthMin": 0.0, "value": 0.9 }
                  ],
                  "interpolation": "LINEAR"
                }
              },
              "metadata": {
                "version": "1.0",
                "name": "test-config"
              }
            }
            """;
        
        ConfigurableQoSEngine engine = ConfigurableQoSEngine.fromJson(json);
        
        // Record healthy latencies
        for (int i = 0; i < 10; i++) {
            engine.record("latency", 80);
        }
        
        QoSOutput output = engine.evaluate();
        
        assertTrue(output.healthScore() >= 0.9);
        assertEquals("HEALTHY", output.status());
        assertNotNull(output.getAllowStaleness());
        assertTrue(output.getAllowStaleness().toSeconds() < 100);  // Should be near min
    }
    
    @Test
    @DisplayName("Step function with LINEAR interpolation")
    void testStepFunctionLinearInterpolation() {
        String json = """
            {
              "evaluator": {
                "rules": [
                  {
                    "metricName": "latency",
                    "percentile": "P95",
                    "weight": 1.0,
                    "stepFunction": {
                      "thresholds": [
                        { "value": 100, "score": 1.0 },
                        { "value": 300, "score": 0.5 },
                        { "value": 500, "score": 0.0 }
                      ],
                      "interpolation": "LINEAR"
                    }
                  }
                ]
              }
            }
            """;
        
        ConfigurableQoSEngine engine = ConfigurableQoSEngine.fromJson(json);
        
        // Record latency at 200ms (midpoint between 100 and 300)
        for (int i = 0; i < 10; i++) {
            engine.record("latency", 200);
        }
        
        QoSOutput output = engine.evaluate();
        
        // Should interpolate: (200-100)/(300-100) = 0.5 of the way from 1.0 to 0.5
        // Score = 1.0 + (0.5 - 1.0) * 0.5 = 0.75
        assertTrue(output.healthScore() >= 0.7 && output.healthScore() <= 0.8);
    }
    
    @Test
    @DisplayName("Multiple rules with weighted aggregation")
    void testMultipleRulesWeightedAggregation() {
        String json = """
            {
              "evaluator": {
                "rules": [
                  {
                    "metricName": "latency",
                    "percentile": "P95",
                    "weight": 0.6,
                    "stepFunction": {
                      "thresholds": [
                        { "value": 100, "score": 1.0 },
                        { "value": 500, "score": 0.0 }
                      ]
                    }
                  },
                  {
                    "metricName": "error_rate",
                    "percentile": "AVG",
                    "weight": 0.4,
                    "stepFunction": {
                      "thresholds": [
                        { "value": 0.01, "score": 1.0 },
                        { "value": 0.1, "score": 0.0 }
                      ]
                    }
                  }
                ],
                "aggregation": "WEIGHTED_AVG"
              }
            }
            """;
        
        ConfigurableQoSEngine engine = ConfigurableQoSEngine.fromJson(json);
        
        // Record healthy latency (100ms) and moderate error rate (5%)
        for (int i = 0; i < 10; i++) {
            engine.record("latency", 100);      // Score = 1.0
            engine.record("error_rate", 0.05);  // Score ~= 0.5
        }
        
        QoSOutput output = engine.evaluate();
        
        // Expected: 0.6 * 1.0 + 0.4 * 0.55 = 0.82
        assertTrue(output.healthScore() >= 0.75 && output.healthScore() <= 0.9);
    }
    
    @Test
    @DisplayName("STEP function for decisions")
    void testStepFunctionDecision() {
        String json = """
            {
              "decisions": {
                "priority": {
                  "type": "INTEGER",
                  "function": {
                    "type": "STEP",
                    "steps": [
                      { "healthMin": 0.8, "value": 1 },
                      { "healthMin": 0.5, "value": 2 },
                      { "healthMin": 0.0, "value": 3 }
                    ]
                  }
                }
              }
            }
            """;
        
        ConfigurableQoSEngine engine = ConfigurableQoSEngine.fromJson(json);
        
        // Healthy system
        for (int i = 0; i < 10; i++) {
            engine.record("latency", 50);
        }
        
        QoSOutput output = engine.evaluate();
        
        Integer priority = output.getInteger("priority");
        assertNotNull(priority);
        assertEquals(1, priority);  // Healthy = priority 1
    }
    
    @Test
    @DisplayName("Step function with interpolation for smooth transitions")
    void testStepFunctionWithInterpolation() {
        String json = """
            {
              "decisions": {
                "smoothTTL": {
                  "type": "DURATION",
                  "steps": [
                    { "healthMin": 1.0, "value": 30 },
                    { "healthMin": 0.7, "value": 60 },
                    { "healthMin": 0.5, "value": 300 },
                    { "healthMin": 0.3, "value": 900 },
                    { "healthMin": 0.0, "value": 1800 }
                  ],
                  "interpolation": "LINEAR"
                }
              }
            }
            """;
        
        ConfigurableQoSEngine engine = ConfigurableQoSEngine.fromJson(json);
        
        // Degraded system
        for (int i = 0; i < 10; i++) {
            engine.record("latency", 300);
        }
        
        QoSOutput output = engine.evaluate();
        
        Duration ttl = output.getDuration("smoothTTL");
        assertNotNull(ttl);
        // Should produce value somewhere in the middle (interpolated)
        assertTrue(ttl.toSeconds() > 100 && ttl.toSeconds() < 1500);
    }
    
    @Test
    @DisplayName("Step function for boolean decisions")
    void testStepFunctionBoolean() {
        String json = """
            {
              "decisions": {
                "enableCache": {
                  "type": "BOOLEAN",
                  "steps": [
                    { "healthMin": 0.3, "value": true },
                    { "healthMin": 0.0, "value": false }
                  ],
                  "interpolation": "STEP"
                }
              }
            }
            """;
        
        ConfigurableQoSEngine engine = ConfigurableQoSEngine.fromJson(json);
        
        // Critical system (health < 0.3)
        for (int i = 0; i < 10; i++) {
            engine.record("latency", 600);
        }
        
        QoSOutput output = engine.evaluate();
        
        Boolean enableCache = output.getBoolean("enableCache");
        assertNotNull(enableCache);
        assertFalse(enableCache);  // Should be disabled when critical
    }
    
    @Test
    @DisplayName("WEIGHTED_MIN aggregation")
    void testWeightedMinAggregation() {
        String json = """
            {
              "evaluator": {
                "rules": [
                  {
                    "metricName": "latency",
                    "percentile": "P95",
                    "weight": 1.0,
                    "stepFunction": {
                      "thresholds": [
                        { "value": 100, "score": 1.0 },
                        { "value": 500, "score": 0.0 }
                      ]
                    }
                  },
                  {
                    "metricName": "error_rate",
                    "percentile": "AVG",
                    "weight": 1.0,
                    "stepFunction": {
                      "thresholds": [
                        { "value": 0.01, "score": 1.0 },
                        { "value": 0.1, "score": 0.0 }
                      ]
                    }
                  }
                ],
                "aggregation": "WEIGHTED_MIN"
              }
            }
            """;
        
        ConfigurableQoSEngine engine = ConfigurableQoSEngine.fromJson(json);
        
        // Good latency but bad error rate
        for (int i = 0; i < 10; i++) {
            engine.record("latency", 50);      // Score = 1.0
            engine.record("error_rate", 0.08); // Score ~= 0.2
        }
        
        QoSOutput output = engine.evaluate();
        
        // WEIGHTED_MIN should use the worst score
        assertTrue(output.healthScore() <= 0.3);
    }
    
    @Test
    @DisplayName("Default configuration works")
    void testDefaultConfiguration() {
        ConfigurableQoSEngine engine = ConfigurableQoSEngine.withDefaults();
        
        for (int i = 0; i < 10; i++) {
            engine.record("latency", 100);
        }
        
        QoSOutput output = engine.evaluate();
        
        assertNotNull(output);
        assertTrue(output.healthScore() >= 0.9);
        assertNotNull(output.getAllowStaleness());
    }
    
    @Test
    @DisplayName("ConfigLoader parses JSON correctly")
    void testConfigLoaderParseJson() {
        String json = """
            {
              "registry": {
                "signalStores": [
                  {
                    "name": "latency",
                    "type": "GAUGE",
                    "bucketSeconds": 10,
                    "maxBuckets": 6,
                    "processor": {
                      "dropNegative": true,
                      "maxValue": 60000
                    }
                  },
                  {
                    "name": "error_rate",
                    "type": "GAUGE",
                    "bucketSeconds": 10,
                    "maxBuckets": 12
                  }
                ]
              },
              "metadata": {
                "version": "2.0",
                "author": "test"
              }
            }
            """;
        
        AdaptiveQoSConfig config = ConfigLoader.fromJson(json);
        
        assertEquals(2, config.registry().signalStores().size());
        assertEquals("latency", config.registry().signalStores().get(0).name());
        assertEquals(10, config.registry().signalStores().get(0).bucketSeconds());
        assertEquals(6, config.registry().signalStores().get(0).maxBuckets());
        // Per-signal processor
        assertTrue(config.registry().signalStores().get(0).processor().dropNegative());
        assertEquals(60000.0, config.registry().signalStores().get(0).processor().maxValue());
        assertEquals("2.0", config.metadata().get("version"));
    }
    
    @Test
    @DisplayName("Complete end-to-end flow with JSON config")
    void testEndToEndFlow() {
        String json = """
            {
              "registry": {
                "signalStores": [
                  { "name": "grpc_latency", "bucketSeconds": 10, "maxBuckets": 6 },
                  { "name": "inference_latency", "bucketSeconds": 10, "maxBuckets": 6 }
                ]
              },
              "evaluator": {
                "rules": [
                  {
                    "metricName": "grpc_latency",
                    "percentile": "P95",
                    "weight": 0.7,
                    "stepFunction": {
                      "thresholds": [
                        { "value": 50, "score": 1.0 },
                        { "value": 100, "score": 0.8 },
                        { "value": 200, "score": 0.5 },
                        { "value": 500, "score": 0.0 }
                      ]
                    }
                  },
                  {
                    "metricName": "inference_latency",
                    "percentile": "P95",
                    "weight": 0.3,
                    "stepFunction": {
                      "thresholds": [
                        { "value": 100, "score": 1.0 },
                        { "value": 300, "score": 0.5 },
                        { "value": 1000, "score": 0.0 }
                      ]
                    }
                  }
                ],
                "aggregation": "WEIGHTED_AVG"
              },
              "decisions": {
                "allowStaleness": {
                  "type": "DURATION",
                  "steps": [
                    { "healthMin": 1.0, "value": 30 },
                    { "healthMin": 0.0, "value": 3600 }
                  ],
                  "interpolation": "LINEAR"
                },
                "throttleRate": {
                  "type": "DOUBLE",
                  "steps": [
                    { "healthMin": 0.5, "value": 0.0 },
                    { "healthMin": 0.0, "value": 0.95 }
                  ],
                  "interpolation": "LINEAR"
                },
                "priority": {
                  "type": "INTEGER",
                  "steps": [
                    { "healthMin": 0.8, "value": 1 },
                    { "healthMin": 0.5, "value": 2 },
                    { "healthMin": 0.2, "value": 3 },
                    { "healthMin": 0.0, "value": 4 }
                  ],
                  "interpolation": "STEP"
                }
              }
            }
            """;
        
        ConfigurableQoSEngine engine = ConfigurableQoSEngine.fromJson(json);
        
        // Simulate real traffic
        for (int i = 0; i < 100; i++) {
            engine.record("grpc_latency", 80 + Math.random() * 40);      // 80-120ms
            engine.record("inference_latency", 150 + Math.random() * 100); // 150-250ms
        }
        
        QoSOutput output = engine.evaluate();
        
        // Verify output structure
        assertNotNull(output.timestamp());
        assertTrue(output.healthScore() >= 0 && output.healthScore() <= 1);
        assertNotNull(output.status());
        
        // Verify decisions
        assertNotNull(output.getAllowStaleness());
        assertNotNull(output.getDouble("throttleRate"));
        assertNotNull(output.getInteger("priority"));
        
        // Verify metrics
        assertTrue(output.metrics().containsKey("grpc_latency_p95"));
        assertTrue(output.metrics().containsKey("inference_latency_p95"));
        
        System.out.println("Health Score: " + output.healthScore());
        System.out.println("Status: " + output.status());
        System.out.println("allowStaleness: " + output.getAllowStaleness());
        System.out.println("Throttle Rate: " + output.getDouble("throttleRate"));
        System.out.println("Priority: " + output.getInteger("priority"));
        System.out.println("Metrics: " + output.metrics());
    }
}
