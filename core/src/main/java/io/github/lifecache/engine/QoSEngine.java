package io.github.lifecache.engine;

import io.github.lifecache.config.*;
import io.github.lifecache.config.QoSEvaluatorConfig.*;
import io.github.lifecache.config.QoSFunctionConfig.*;
import io.github.lifecache.config.QoSOutput.*;
import io.github.lifecache.metrics.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * QoS Engine - JSON config driven health evaluation and decision making.
 * 
 * <p>Architecture:</p>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │                        QoSEngine                            │
 * │  ┌─────────────┐  ┌──────────────┐  ┌───────────────────┐  │
 * │  │MetricsConfig│  │EvaluatorConfig│ │FunctionConfig     │  │
 * │  │   (JSON)    │  │   (JSON)      │ │   (JSON)          │  │
 * │  └──────┬──────┘  └───────┬───────┘ └─────────┬─────────┘  │
 * │         │                 │                   │            │
 * │         ▼                 ▼                   ▼            │
 * │  ┌─────────────┐  ┌──────────────┐  ┌───────────────────┐  │
 * │  │MetricsBackend│→│ QoSEvaluator │→│DecisionGenerator │  │
 * │  └─────────────┘  └──────────────┘  └───────────────────┘  │
 * │                           │                   │            │
 * │                           └───────┬───────────┘            │
 * │                                   ▼                        │
 * │                          ┌───────────────┐                 │
 * │                          │  QoSOutput    │                 │
 * │                          │   (JSON)      │                 │
 * │                          └───────────────┘                 │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class QoSEngine {
    
    private final MetricsConfig metricsConfig;
    private final QoSEvaluatorConfig evaluatorConfig;
    private final QoSFunctionConfig functionConfig;
    
    private final RoutedMetricsRegistry metricsRegistry;
    
    public QoSEngine(MetricsConfig metricsConfig, 
                     QoSEvaluatorConfig evaluatorConfig,
                     QoSFunctionConfig functionConfig) {
        this.metricsConfig = metricsConfig;
        this.evaluatorConfig = evaluatorConfig;
        this.functionConfig = functionConfig;
        
        // Initialize metrics registry based on config
        this.metricsRegistry = initializeRegistry(metricsConfig);
    }
    
    private RoutedMetricsRegistry initializeRegistry(MetricsConfig config) {
        RoutedMetricsRegistry registry = new RoutedMetricsRegistry(
            config.window()
        );
        
        // Configure each metric with per-signal processor
        for (MetricsConfig.MetricDefinition def : config.metrics()) {
            AdaptiveQoSConfig.ProcessorConfig processor = new AdaptiveQoSConfig.ProcessorConfig(
                def.dropNegative(),
                def.maxValue(),
                null
            );
            registry.configureGauge(def.name(), processor);
        }
        
        return registry;
    }
    
    // ============ Metrics Recording ============
    
    /**
     * Record a metric sample.
     */
    public void record(String metricName, double value) {
        metricsRegistry.record(MetricSample.of(metricName, value));
    }
    
    /**
     * Record a metric sample.
     */
    public void record(MetricSample sample) {
        metricsRegistry.record(sample);
    }
    
    // ============ QoS Evaluation ============
    
    /**
     * Evaluate current QoS and return JSON-serializable output.
     */
    public QoSOutput evaluate() {
        // Step 1: Evaluate health score from metrics
        double healthScore = evaluateHealth();
        
        // Step 2: Generate decisions from health score
        Map<String, DecisionValue> decisions = generateDecisions(healthScore);
        
        // Step 3: Collect current metric values
        Map<String, Double> metricValues = collectMetricValues();
        
        // Step 4: Build output
        return QoSOutput.builder()
            .timestamp(Instant.now())
            .healthScore(healthScore)
            .decisions(decisions)
            .metrics(metricValues)
            .metadata("configVersion", "1.0")
            .build();
    }
    
    // ============ Health Evaluation ============
    
    private double evaluateHealth() {
        List<HealthRule> rules = evaluatorConfig.healthRules();
        if (rules.isEmpty()) {
            return 1.0;  // No rules = healthy
        }
        
        double totalWeight = 0;
        double weightedSum = 0;
        double minScore = 1.0;
        double maxScore = 0.0;
        
        for (HealthRule rule : rules) {
            Double metricValue = readMetric(rule.metricName(), rule.percentile(), rule.window());
            if (metricValue == null) {
                continue;  // Skip if no data
            }
            
            double ruleScore = evaluateRule(rule, metricValue);
            totalWeight += rule.weight();
            weightedSum += ruleScore * rule.weight();
            minScore = Math.min(minScore, ruleScore);
            maxScore = Math.max(maxScore, ruleScore);
        }
        
        if (totalWeight == 0) {
            return 1.0;  // No valid rules = healthy
        }
        
        return switch (QoSEvaluatorConfig.Aggregation.valueOf(evaluatorConfig.aggregation())) {
            case WEIGHTED_AVG -> weightedSum / totalWeight;
            case WEIGHTED_MIN -> minScore;
            case WEIGHTED_MAX -> maxScore;
            case FIRST_MATCH -> weightedSum / totalWeight;  // Fallback to avg
        };
    }
    
    private double evaluateRule(HealthRule rule, double metricValue) {
        List<Threshold> thresholds = rule.thresholds().stream()
            .sorted(Comparator.comparingDouble(Threshold::value))
            .toList();
        
        if (thresholds.isEmpty()) {
            return 1.0;
        }
        
        // Below first threshold
        if (metricValue <= thresholds.get(0).value()) {
            return thresholds.get(0).healthScore();
        }
        
        // Above last threshold
        if (metricValue >= thresholds.get(thresholds.size() - 1).value()) {
            return thresholds.get(thresholds.size() - 1).healthScore();
        }
        
        // Linear interpolation between thresholds
        for (int i = 1; i < thresholds.size(); i++) {
            Threshold prev = thresholds.get(i - 1);
            Threshold curr = thresholds.get(i);
            
            if (metricValue <= curr.value()) {
                double ratio = (metricValue - prev.value()) / (curr.value() - prev.value());
                return prev.healthScore() + (curr.healthScore() - prev.healthScore()) * ratio;
            }
        }
        
        return thresholds.get(thresholds.size() - 1).healthScore();
    }
    
    private Double readMetric(String metricName, String percentile, java.time.Duration window) {
        io.github.lifecache.metrics.Aggregation a = io.github.lifecache.metrics.Aggregation.valueOf(percentile);
        return metricsRegistry.read(metricName, a, window);
    }
    
    // ============ Decision Generation ============
    
    private Map<String, DecisionValue> generateDecisions(double healthScore) {
        Map<String, DecisionValue> decisions = new HashMap<>();
        
        for (OutputDefinition output : functionConfig.outputs()) {
            DecisionValue value = generateDecision(output, healthScore);
            decisions.put(output.name(), value);
        }
        
        return decisions;
    }
    
    private DecisionValue generateDecision(OutputDefinition output, double healthScore) {
        Map<String, Object> params = output.params();
        
        return switch (output.function()) {
            case LINEAR -> generateLinear(output, healthScore, params);
            case STEP -> generateStep(output, healthScore, params);
            case SIGMOID -> generateSigmoid(output, healthScore, params);
            case THRESHOLD -> generateThreshold(output, healthScore, params);
            case CONSTANT -> generateConstant(output, params);
        };
    }
    
    private DecisionValue generateLinear(OutputDefinition output, double health, Map<String, Object> params) {
        return switch (output.type()) {
            case DURATION -> {
                long minValue = getParamLong(params, "minValue", 30);
                long maxValue = getParamLong(params, "maxValue", 1800);
                long value = (long) (minValue + (maxValue - minValue) * (1.0 - health));
                yield DecisionValue.ofDuration(Duration.ofSeconds(value));
            }
            case DOUBLE -> {
                // For throttle rate: starts at threshold, increases to max
                double startThreshold = getParamDouble(params, "startThreshold", 0.5);
                double maxRate = getParamDouble(params, "maxRate", 0.9);
                double rate = 0.0;
                if (health < startThreshold) {
                    rate = (startThreshold - health) / startThreshold * maxRate;
                }
                yield DecisionValue.ofDouble(rate);
            }
            case INTEGER -> {
                int minValue = getParamInt(params, "minValue", 1);
                int maxValue = getParamInt(params, "maxValue", 10);
                int value = (int) (minValue + (maxValue - minValue) * (1.0 - health));
                yield DecisionValue.ofInteger(value);
            }
            case BOOLEAN -> {
                double threshold = getParamDouble(params, "threshold", 0.5);
                yield DecisionValue.ofBoolean(health >= threshold);
            }
            case STRING -> DecisionValue.ofString(String.valueOf(health));
        };
    }
    
    @SuppressWarnings("unchecked")
    private DecisionValue generateStep(OutputDefinition output, double health, Map<String, Object> params) {
        List<Map<String, Object>> steps = (List<Map<String, Object>>) params.get("steps");
        if (steps == null || steps.isEmpty()) {
            return DecisionValue.ofDouble(0);
        }
        
        // Sort by healthMin descending
        steps = steps.stream()
            .sorted((a, b) -> Double.compare(
                getParamDouble(b, "healthMin", 0),
                getParamDouble(a, "healthMin", 0)))
            .toList();
        
        for (Map<String, Object> step : steps) {
            double healthMin = getParamDouble(step, "healthMin", 0);
            if (health >= healthMin) {
                Object value = step.get("value");
                return toDecisionValue(output.type(), value);
            }
        }
        
        // Return last step value
        return toDecisionValue(output.type(), steps.get(steps.size() - 1).get("value"));
    }
    
    private DecisionValue generateSigmoid(OutputDefinition output, double health, Map<String, Object> params) {
        double steepness = getParamDouble(params, "steepness", 10.0);
        double x = (0.5 - health) * steepness;
        double weight = 1.0 / (1.0 + Math.exp(-x));
        
        return switch (output.type()) {
            case DURATION -> {
                long minValue = getParamLong(params, "minValue", 30);
                long maxValue = getParamLong(params, "maxValue", 1800);
                long value = (long) (minValue + (maxValue - minValue) * weight);
                yield DecisionValue.ofDuration(Duration.ofSeconds(value));
            }
            case DOUBLE -> {
                double minValue = getParamDouble(params, "minValue", 0);
                double maxValue = getParamDouble(params, "maxValue", 1);
                yield DecisionValue.ofDouble(minValue + (maxValue - minValue) * weight);
            }
            default -> generateLinear(output, health, params);
        };
    }
    
    private DecisionValue generateThreshold(OutputDefinition output, double health, Map<String, Object> params) {
        double threshold = getParamDouble(params, "threshold", 0.5);
        Object belowValue = params.get("below");
        Object aboveValue = params.get("above");
        
        Object value = health >= threshold ? aboveValue : belowValue;
        return toDecisionValue(output.type(), value);
    }
    
    private DecisionValue generateConstant(OutputDefinition output, Map<String, Object> params) {
        Object value = params.get("value");
        return toDecisionValue(output.type(), value);
    }
    
    private DecisionValue toDecisionValue(OutputType type, Object value) {
        return switch (type) {
            case DOUBLE -> DecisionValue.ofDouble(((Number) value).doubleValue());
            case INTEGER -> DecisionValue.ofInteger(((Number) value).intValue());
            case BOOLEAN -> DecisionValue.ofBoolean((Boolean) value);
            case DURATION -> DecisionValue.ofDuration(Duration.ofSeconds(((Number) value).longValue()));
            case STRING -> DecisionValue.ofString(String.valueOf(value));
        };
    }
    
    // ============ Metric Collection ============
    
    private Map<String, Double> collectMetricValues() {
        Map<String, Double> values = new HashMap<>();
        
        for (HealthRule rule : evaluatorConfig.healthRules()) {
            Double value = readMetric(rule.metricName(), rule.percentile(), rule.window());
            if (value != null) {
                values.put(rule.metricName() + "_" + rule.percentile().toLowerCase(), value);
            }
        }
        
        return values;
    }
    
    // ============ Param Helpers ============
    
    private double getParamDouble(Map<String, Object> params, String key, double defaultValue) {
        Object value = params.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return defaultValue;
    }
    
    private int getParamInt(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }
    
    private long getParamLong(Map<String, Object> params, String key, long defaultValue) {
        Object value = params.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return defaultValue;
    }
    
    // ============ Accessors ============
    
    public MetricsReader getMetricsReader() {
        return metricsRegistry;
    }
    
    public MetricsWriter getMetricsWriter() {
        return metricsRegistry;
    }
    
    // ============ Builder ============
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private MetricsConfig metricsConfig = MetricsConfig.defaultLatencyConfig();
        private QoSEvaluatorConfig evaluatorConfig = QoSEvaluatorConfig.defaultLatencyConfig();
        private QoSFunctionConfig functionConfig = QoSFunctionConfig.defaults();
        
        public Builder metricsConfig(MetricsConfig config) {
            this.metricsConfig = config;
            return this;
        }
        
        public Builder evaluatorConfig(QoSEvaluatorConfig config) {
            this.evaluatorConfig = config;
            return this;
        }
        
        public Builder functionConfig(QoSFunctionConfig config) {
            this.functionConfig = config;
            return this;
        }
        
        public QoSEngine build() {
            return new QoSEngine(metricsConfig, evaluatorConfig, functionConfig);
        }
    }
}
