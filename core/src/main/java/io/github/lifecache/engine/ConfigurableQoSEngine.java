package io.github.lifecache.engine;

import io.github.lifecache.config.*;
import io.github.lifecache.config.AdaptiveQoSConfig.*;
import io.github.lifecache.config.QoSOutput.*;
import io.github.lifecache.metrics.*;
import io.github.lifecache.config.AdaptiveQoSConfig.RegistryConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Fully JSON-configurable QoS Engine.
 * All components (SignalStore, Registry, Evaluator, Decisions) configured via JSON.
 * 
 * <p>Usage:</p>
 * <pre>{@code
 * // From JSON string
 * ConfigurableQoSEngine engine = ConfigurableQoSEngine.fromJson(jsonConfig);
 * 
 * // From file
 * ConfigurableQoSEngine engine = ConfigurableQoSEngine.fromFile("config.json");
 * 
 * // Record metrics
 * engine.record("grpc_latency", 150);
 * 
 * // Get QoS output (JSON-serializable)
 * QoSOutput output = engine.evaluate();
 * }</pre>
 */
public class ConfigurableQoSEngine {
    
    private final AdaptiveQoSConfig config;
    private final RoutedMetricsRegistry registry;
    
    private ConfigurableQoSEngine(AdaptiveQoSConfig config) {
        this.config = config;
        this.registry = buildRegistry(config.registry());
    }
    
    // ============ Factory Methods ============
    
    /**
     * Create from JSON string.
     */
    public static ConfigurableQoSEngine fromJson(String json) {
        return new ConfigurableQoSEngine(ConfigLoader.fromJson(json));
    }
    
    /**
     * Create from file.
     */
    public static ConfigurableQoSEngine fromFile(Path path) throws IOException {
        return new ConfigurableQoSEngine(ConfigLoader.fromFile(path));
    }
    
    /**
     * Create from file path.
     */
    public static ConfigurableQoSEngine fromFile(String path) throws IOException {
        return new ConfigurableQoSEngine(ConfigLoader.fromFile(path));
    }
    
    /**
     * Create from classpath resource.
     */
    public static ConfigurableQoSEngine fromResource(String resourcePath) throws IOException {
        return new ConfigurableQoSEngine(ConfigLoader.fromResource(resourcePath));
    }
    
    /**
     * Create from config object.
     */
    public static ConfigurableQoSEngine fromConfig(AdaptiveQoSConfig config) {
        return new ConfigurableQoSEngine(config);
    }
    
    /**
     * Create with defaults.
     */
    public static ConfigurableQoSEngine withDefaults() {
        return new ConfigurableQoSEngine(AdaptiveQoSConfig.defaults());
    }
    
    // ============ Build Registry from Config ============
    
    private RoutedMetricsRegistry buildRegistry(RegistryConfig config) {
        // Use first signal store's window, or default 60 seconds
        Duration defaultWindow = Duration.ofSeconds(60);
        if (!config.signalStores().isEmpty()) {
            defaultWindow = config.signalStores().get(0).window();
        }
        // Processor config is available via config.processor() for future use
        return new RoutedMetricsRegistry(defaultWindow);
    }
    
    // ============ Recording ============
    
    /**
     * Record a metric sample.
     */
    public void record(String metricName, double value) {
        registry.record(MetricSample.of(metricName, value));
    }
    
    /**
     * Record a metric sample.
     */
    public void record(MetricSample sample) {
        registry.record(sample);
    }
    
    // ============ Evaluation ============
    
    /**
     * Evaluate current QoS state.
     */
    public QoSOutput evaluate() {
        // Step 1: Evaluate health from rules
        double healthScore = evaluateHealth();
        
        // Step 2: Generate decisions from health
        Map<String, DecisionValue> decisions = generateDecisions(healthScore);
        
        // Step 3: Collect metric values for output
        Map<String, Double> metricValues = collectMetrics();
        
        // Step 4: Build output
        return QoSOutput.builder()
            .timestamp(Instant.now())
            .healthScore(healthScore)
            .decisions(decisions)
            .metrics(metricValues)
            .metadata("configVersion", config.metadata().getOrDefault("version", "1.0"))
            .build();
    }
    
    // ============ Health Evaluation ============
    
    private double evaluateHealth() {
        EvaluatorConfig evalConfig = config.evaluator();
        List<RuleConfig> rules = evalConfig.rules();
        
        if (rules.isEmpty()) {
            return 1.0;
        }
        
        double totalWeight = 0;
        double weightedSum = 0;
        double minScore = 1.0;
        double maxScore = 0.0;
        
        for (RuleConfig rule : rules) {
            Double metricValue = readMetric(rule.metricName(), rule.percentile(), rule.window());
            if (metricValue == null) {
                continue;
            }
            
            double ruleScore = evaluateStepFunction(rule.stepFunction(), metricValue);
            totalWeight += rule.weight();
            weightedSum += ruleScore * rule.weight();
            minScore = Math.min(minScore, ruleScore);
            maxScore = Math.max(maxScore, ruleScore);
        }
        
        if (totalWeight == 0) {
            return 1.0;
        }
        
        return switch (evalConfig.aggregation()) {
            case "WEIGHTED_MIN" -> minScore;
            case "WEIGHTED_MAX" -> maxScore;
            default -> weightedSum / totalWeight; // WEIGHTED_AVG
        };
    }
    
    private double evaluateStepFunction(StepFunctionConfig config, double value) {
        List<ThresholdConfig> thresholds = config.thresholds().stream()
            .sorted(Comparator.comparingDouble(ThresholdConfig::value))
            .toList();
        
        if (thresholds.isEmpty()) {
            return 1.0;
        }
        
        // Below first threshold
        if (value <= thresholds.get(0).value()) {
            return thresholds.get(0).score();
        }
        
        // Above last threshold
        if (value >= thresholds.get(thresholds.size() - 1).value()) {
            return thresholds.get(thresholds.size() - 1).score();
        }
        
        // Interpolate between thresholds
        if (config.interpolation().equals("LINEAR")) {
            for (int i = 1; i < thresholds.size(); i++) {
                ThresholdConfig prev = thresholds.get(i - 1);
                ThresholdConfig curr = thresholds.get(i);
                
                if (value <= curr.value()) {
                    double ratio = (value - prev.value()) / (curr.value() - prev.value());
                    return prev.score() + (curr.score() - prev.score()) * ratio;
                }
            }
        }
        
        // STEP interpolation - find exact step
        for (int i = thresholds.size() - 1; i >= 0; i--) {
            if (value >= thresholds.get(i).value()) {
                return thresholds.get(i).score();
            }
        }
        
        return thresholds.get(0).score();
    }
    
    private Double readMetric(String metricName, String percentile, java.time.Duration window) {
        Aggregation a = Aggregation.valueOf(percentile);
        return registry.read(metricName, a, window);
    }
    
    // ============ Decision Generation (Step Function Only) ============
    
    private Map<String, DecisionValue> generateDecisions(double healthScore) {
        Map<String, DecisionValue> decisions = new LinkedHashMap<>();
        
        for (Map.Entry<String, DecisionConfig> entry : config.decisions().entrySet()) {
            DecisionValue value = generateStepDecision(entry.getValue(), healthScore);
            decisions.put(entry.getKey(), value);
        }
        
        return decisions;
    }
    
    private DecisionValue generateStepDecision(DecisionConfig config, double health) {
        List<StepConfig> steps = config.steps();
        if (steps == null || steps.isEmpty()) {
            return DecisionValue.ofDouble(0);
        }
        
        // Sort by healthMin descending
        List<StepConfig> sorted = steps.stream()
            .sorted((a, b) -> Double.compare(b.healthMin(), a.healthMin()))
            .toList();
        
        String type = config.type();
        boolean interpolate = "LINEAR".equals(config.interpolation());
        
        // Find value with optional interpolation
        if (interpolate && sorted.size() >= 2) {
            for (int i = 0; i < sorted.size() - 1; i++) {
                StepConfig upper = sorted.get(i);
                StepConfig lower = sorted.get(i + 1);
                
                if (health <= upper.healthMin() && health >= lower.healthMin()) {
                    double ratio = (upper.healthMin() - health) / (upper.healthMin() - lower.healthMin());
                    return interpolateValue(type, upper.value(), lower.value(), ratio);
                }
            }
        }
        
        // Step function (no interpolation)
        for (StepConfig step : sorted) {
            if (health >= step.healthMin()) {
                return toDecisionValue(type, step.value());
            }
        }
        
        return toDecisionValue(type, sorted.get(sorted.size() - 1).value());
    }
    
    private DecisionValue interpolateValue(String type, Object upper, Object lower, double ratio) {
        double upperVal = ((Number) upper).doubleValue();
        double lowerVal = ((Number) lower).doubleValue();
        double interpolated = upperVal + (lowerVal - upperVal) * ratio;
        
        return switch (type) {
            case "DURATION" -> DecisionValue.ofDuration(Duration.ofSeconds((long) interpolated));
            case "INTEGER" -> DecisionValue.ofInteger((int) interpolated);
            default -> DecisionValue.ofDouble(interpolated);
        };
    }
    
    private DecisionValue toDecisionValue(String type, Object value) {
        if (value == null) return DecisionValue.ofDouble(0);
        
        return switch (type) {
            case "DURATION" -> DecisionValue.ofDuration(
                Duration.ofSeconds(((Number) value).longValue()));
            case "INTEGER" -> DecisionValue.ofInteger(((Number) value).intValue());
            case "BOOLEAN" -> DecisionValue.ofBoolean((Boolean) value);
            case "STRING" -> DecisionValue.ofString(String.valueOf(value));
            default -> DecisionValue.ofDouble(((Number) value).doubleValue());
        };
    }
    
    // ============ Metric Collection ============
    
    private Map<String, Double> collectMetrics() {
        Map<String, Double> values = new LinkedHashMap<>();
        
        for (RuleConfig rule : config.evaluator().rules()) {
            Double value = readMetric(rule.metricName(), rule.percentile(), rule.window());
            if (value != null) {
                values.put(rule.metricName() + "_" + rule.percentile().toLowerCase(), value);
            }
        }
        
        return values;
    }
    
    // ============ Accessors ============
    
    public AdaptiveQoSConfig getConfig() {
        return config;
    }
    
    public MetricsReader getMetricsReader() {
        return registry;
    }
    
    public MetricsWriter getMetricsWriter() {
        return registry;
    }
}
