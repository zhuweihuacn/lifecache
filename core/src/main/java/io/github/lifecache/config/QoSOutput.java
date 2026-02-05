package io.github.lifecache.config;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * JSON-serializable QoS Output.
 * 
 * <p>Example JSON Output:</p>
 * <pre>{@code
 * {
 *   "timestamp": "2026-02-04T10:30:00Z",
 *   "healthScore": 0.65,
 *   "status": "DEGRADED",
 *   "decisions": {
 *     "allowStaleness": {
 *       "type": "DURATION",
 *       "value": 300,
 *       "unit": "SECONDS",
 *       "formatted": "PT5M"
 *     },
 *     "throttleRate": {
 *       "type": "DOUBLE",
 *       "value": 0.15
 *     },
 *     "priority": {
 *       "type": "INTEGER",
 *       "value": 2
 *     },
 *     "cacheEnabled": {
 *       "type": "BOOLEAN",
 *       "value": true
 *     }
 *   },
 *   "metrics": {
 *     "grpc_latency_p95": 180.5,
 *     "error_rate_avg": 0.02,
 *     "sample_count": 1523
 *   },
 *   "metadata": {
 *     "evaluatorVersion": "1.0",
 *     "configHash": "abc123"
 *   }
 * }
 * }</pre>
 */
public record QoSOutput(
    Instant timestamp,
    double healthScore,
    String status,
    Map<String, DecisionValue> decisions,
    Map<String, Double> metrics,
    Map<String, Object> metadata
) {
    
    public QoSOutput {
        if (timestamp == null) {
            timestamp = Instant.now();
        }
        if (healthScore < 0 || healthScore > 1) {
            throw new IllegalArgumentException("Health score must be 0.0 - 1.0");
        }
        if (status == null || status.isBlank()) {
            status = getStatusFromHealth(healthScore);
        }
        if (decisions == null) {
            decisions = Map.of();
        }
        if (metrics == null) {
            metrics = Map.of();
        }
        if (metadata == null) {
            metadata = Map.of();
        }
    }
    
    private static String getStatusFromHealth(double health) {
        if (health >= 0.8) return "HEALTHY";
        if (health >= 0.5) return "DEGRADED";
        if (health >= 0.2) return "STRESSED";
        return "CRITICAL";
    }
    
    // ============ Convenience Getters ============
    
    /**
     * Get duration decision value.
     */
    public Duration getDuration(String name) {
        DecisionValue value = decisions.get(name);
        if (value == null || value.type() != QoSFunctionConfig.OutputType.DURATION) {
            return null;
        }
        return Duration.ofSeconds(((Number) value.value()).longValue());
    }
    
    /**
     * Get double decision value.
     */
    public Double getDouble(String name) {
        DecisionValue value = decisions.get(name);
        if (value == null) return null;
        return ((Number) value.value()).doubleValue();
    }
    
    /**
     * Get integer decision value.
     */
    public Integer getInteger(String name) {
        DecisionValue value = decisions.get(name);
        if (value == null) return null;
        return ((Number) value.value()).intValue();
    }
    
    /**
     * Get boolean decision value.
     */
    public Boolean getBoolean(String name) {
        DecisionValue value = decisions.get(name);
        if (value == null) return null;
        return (Boolean) value.value();
    }
    
    /**
     * Check if should throttle (probabilistic).
     */
    public boolean shouldThrottle() {
        Double rate = getDouble("throttleRate");
        if (rate == null || rate <= 0) return false;
        return Math.random() < rate;
    }
    
    /**
     * Get allowed staleness duration.
     */
    public Duration getAllowStaleness() {
        return getDuration("allowStaleness");
    }
    
    // ============ Builder ============
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private Instant timestamp = Instant.now();
        private double healthScore = 1.0;
        private String status;
        private final Map<String, DecisionValue> decisions = new HashMap<>();
        private final Map<String, Double> metrics = new HashMap<>();
        private final Map<String, Object> metadata = new HashMap<>();
        
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }
        
        public Builder healthScore(double score) {
            this.healthScore = score;
            return this;
        }
        
        public Builder status(String status) {
            this.status = status;
            return this;
        }
        
        public Builder decision(String name, DecisionValue value) {
            this.decisions.put(name, value);
            return this;
        }
        
        public Builder decision(String name, double value) {
            return decision(name, DecisionValue.ofDouble(value));
        }
        
        public Builder decision(String name, int value) {
            return decision(name, DecisionValue.ofInteger(value));
        }
        
        public Builder decision(String name, boolean value) {
            return decision(name, DecisionValue.ofBoolean(value));
        }
        
        public Builder decision(String name, Duration value) {
            return decision(name, DecisionValue.ofDuration(value));
        }
        
        public Builder metric(String name, double value) {
            this.metrics.put(name, value);
            return this;
        }
        
        public Builder metrics(Map<String, Double> metrics) {
            this.metrics.putAll(metrics);
            return this;
        }
        
        public Builder decisions(Map<String, DecisionValue> decisions) {
            this.decisions.putAll(decisions);
            return this;
        }
        
        public Builder metadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }
        
        public QoSOutput build() {
            return new QoSOutput(timestamp, healthScore, status, decisions, metrics, metadata);
        }
    }
    
    // ============ Decision Value ============
    
    /**
     * Single decision value with type info.
     */
    public record DecisionValue(
        QoSFunctionConfig.OutputType type,
        Object value,
        String unit,
        String formatted
    ) {
        public DecisionValue {
            if (type == null) {
                type = QoSFunctionConfig.OutputType.DOUBLE;
            }
        }
        
        public static DecisionValue ofDouble(double value) {
            return new DecisionValue(QoSFunctionConfig.OutputType.DOUBLE, value, null, null);
        }
        
        public static DecisionValue ofInteger(int value) {
            return new DecisionValue(QoSFunctionConfig.OutputType.INTEGER, value, null, null);
        }
        
        public static DecisionValue ofBoolean(boolean value) {
            return new DecisionValue(QoSFunctionConfig.OutputType.BOOLEAN, value, null, null);
        }
        
        public static DecisionValue ofDuration(Duration duration) {
            return new DecisionValue(
                QoSFunctionConfig.OutputType.DURATION,
                duration.toSeconds(),
                "SECONDS",
                duration.toString()
            );
        }
        
        public static DecisionValue ofString(String value) {
            return new DecisionValue(QoSFunctionConfig.OutputType.STRING, value, null, null);
        }
        
        // ============ Convenience Accessors ============
        
        /**
         * Get value as double.
         */
        public double asDouble() {
            if (value instanceof Number n) {
                return n.doubleValue();
            }
            return 0.0;
        }
        
        /**
         * Get value as int.
         */
        public int asInt() {
            if (value instanceof Number n) {
                return n.intValue();
            }
            return 0;
        }
        
        /**
         * Get value as boolean.
         */
        public boolean asBoolean() {
            if (value instanceof Boolean b) {
                return b;
            }
            return false;
        }
        
        /**
         * Get value as Duration.
         */
        public Duration asDuration() {
            if (value instanceof Number n) {
                return Duration.ofSeconds(n.longValue());
            }
            return Duration.ZERO;
        }
        
        /**
         * Get value as String.
         */
        public String asString() {
            return value != null ? value.toString() : "";
        }
    }
}
