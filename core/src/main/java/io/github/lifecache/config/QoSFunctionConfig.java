package io.github.lifecache.config;

import java.util.List;
import java.util.Map;

/**
 * JSON-serializable QoS Function Configuration.
 * Defines how health score maps to QoS decisions.
 * 
 * <p>Example JSON:</p>
 * <pre>{@code
 * {
 *   "outputs": [
 *     {
 *       "name": "allowStaleness",
 *       "type": "DURATION",
 *       "function": "LINEAR",
 *       "params": {
 *         "minValue": 30,
 *         "maxValue": 1800,
 *         "unit": "SECONDS"
 *       }
 *     },
 *     {
 *       "name": "throttleRate",
 *       "type": "DOUBLE",
 *       "function": "LINEAR",
 *       "params": {
 *         "startThreshold": 0.5,
 *         "maxRate": 0.9
 *       }
 *     },
 *     {
 *       "name": "priority",
 *       "type": "INTEGER",
 *       "function": "STEP",
 *       "params": {
 *         "steps": [
 *           { "healthMin": 0.8, "value": 1 },
 *           { "healthMin": 0.5, "value": 2 },
 *           { "healthMin": 0.0, "value": 3 }
 *         ]
 *       }
 *     },
 *     {
 *       "name": "cacheEnabled",
 *       "type": "BOOLEAN",
 *       "function": "THRESHOLD",
 *       "params": {
 *         "threshold": 0.3,
 *         "below": true,
 *         "above": false
 *       }
 *     }
 *   ]
 * }
 * }</pre>
 */
public record QoSFunctionConfig(
    List<OutputDefinition> outputs
) {
    
    public QoSFunctionConfig {
        if (outputs == null) {
            outputs = List.of();
        }
    }
    
    /**
     * Create default config with allowStaleness and throttle.
     */
    public static QoSFunctionConfig defaults() {
        return new QoSFunctionConfig(List.of(
            new OutputDefinition(
                "allowStaleness",
                OutputType.DURATION,
                FunctionType.LINEAR,
                Map.of("minValue", 30, "maxValue", 1800, "unit", "SECONDS")
            ),
            new OutputDefinition(
                "throttleRate",
                OutputType.DOUBLE,
                FunctionType.LINEAR,
                Map.of("startThreshold", 0.5, "maxRate", 0.9)
            )
        ));
    }
    
    /**
     * Single output definition.
     */
    public record OutputDefinition(
        String name,
        OutputType type,
        FunctionType function,
        Map<String, Object> params
    ) {
        public OutputDefinition {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Output name is required");
            }
            if (type == null) {
                type = OutputType.DOUBLE;
            }
            if (function == null) {
                function = FunctionType.LINEAR;
            }
            if (params == null) {
                params = Map.of();
            }
        }
    }
    
    /**
     * Output value types.
     */
    public enum OutputType {
        DOUBLE,
        INTEGER,
        BOOLEAN,
        DURATION,
        STRING
    }
    
    /**
     * Function types for mapping health to output.
     */
    public enum FunctionType {
        LINEAR,     // Linear interpolation: health 1.0 → min, health 0.0 → max
        STEP,       // Step function with thresholds
        SIGMOID,    // S-curve transition
        THRESHOLD,  // Simple threshold comparison
        CONSTANT    // Fixed value regardless of health
    }
}
