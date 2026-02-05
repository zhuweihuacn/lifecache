package io.github.lifecache.decision;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Decision rule using step function: health score → decision value.
 * 
 * <p>All decision rules are step functions with optional interpolation between steps.</p>
 * 
 * <pre>{@code
 * // Define rules using step function
 * DecisionRule<Duration> ttlRule = DecisionRule.steps(
 *     step(1.0, Duration.ofSeconds(30)),
 *     step(0.5, Duration.ofMinutes(5)),
 *     step(0.0, Duration.ofMinutes(30))
 * );
 * 
 * DecisionRule<Double> throttleRule = DecisionRule.steps(
 *     step(0.5, 0.0),    // health >= 0.5 → no throttle
 *     step(0.3, 0.5),    // health >= 0.3 → 50% throttle
 *     step(0.0, 0.9)     // health < 0.3 → 90% throttle
 * );
 * 
 * DecisionRule<Integer> priorityRule = DecisionRule.steps(
 *     step(0.8, 1),
 *     step(0.5, 2),
 *     step(0.0, 3)
 * );
 * 
 * // Use rules
 * double health = 0.6;
 * Duration ttl = ttlRule.apply(health);
 * Double rate = throttleRule.apply(health);
 * }</pre>
 *
 * @param <T> the decision value type
 */
@FunctionalInterface
public interface DecisionRule<T> {
    
    /**
     * Generate decision value from health score.
     *
     * @param healthScore health score 0.0 (critical) ~ 1.0 (healthy)
     * @return decision value
     */
    T apply(double healthScore);
    
    /**
     * Evaluate this rule and return a QoSDecision.
     * 
     * <pre>{@code
     * DecisionRule<Duration> ttlRule = DecisionRule.stepsWithInterpolation(...);
     * QoSDecision<Duration> decision = ttlRule.evaluate(health);
     * Duration ttl = decision.value();
     * }</pre>
     *
     * @param healthScore health score 0.0 (critical) ~ 1.0 (healthy)
     * @return QoSDecision containing the evaluated value
     */
    default QoSDecision<T> evaluate(double healthScore) {
        return QoSDecision.of(apply(healthScore));
    }
    
    // ============ Step Function (Primary API) ============
    
    /**
     * Create a step function rule.
     * 
     * <pre>{@code
     * DecisionRule<Integer> priority = DecisionRule.steps(
     *     step(0.8, 1),   // health >= 0.8 → 1
     *     step(0.5, 2),   // health >= 0.5 → 2
     *     step(0.0, 3)    // health < 0.5 → 3
     * );
     * }</pre>
     */
    @SafeVarargs
    static <T> DecisionRule<T> steps(Step<T>... steps) {
        List<Step<T>> sorted = Arrays.stream(steps)
            .sorted((a, b) -> Double.compare(b.healthMin(), a.healthMin()))  // descending
            .toList();
        
        return health -> {
            for (Step<T> s : sorted) {
                if (health >= s.healthMin()) {
                    return s.value();
                }
            }
            return sorted.get(sorted.size() - 1).value();
        };
    }
    
    /**
     * Create a step function with Duration interpolation between steps.
     */
    @SafeVarargs
    static DecisionRule<Duration> stepsWithInterpolation(Step<Duration>... steps) {
        List<Step<Duration>> sorted = Arrays.stream(steps)
            .sorted((a, b) -> Double.compare(b.healthMin(), a.healthMin()))  // descending
            .toList();
        
        return health -> {
            if (sorted.isEmpty()) {
                return Duration.ofMinutes(1);
            }
            
            // Find surrounding steps and interpolate
            for (int i = 0; i < sorted.size() - 1; i++) {
                Step<Duration> upper = sorted.get(i);
                Step<Duration> lower = sorted.get(i + 1);
                
                if (health <= upper.healthMin() && health >= lower.healthMin()) {
                    double ratio = (upper.healthMin() - health) / (upper.healthMin() - lower.healthMin());
                    long upperMs = upper.value().toMillis();
                    long lowerMs = lower.value().toMillis();
                    return Duration.ofMillis((long) (upperMs + (lowerMs - upperMs) * ratio));
                }
            }
            
            if (health >= sorted.get(0).healthMin()) {
                return sorted.get(0).value();
            }
            return sorted.get(sorted.size() - 1).value();
        };
    }
    
    /**
     * Create a step function with Double interpolation between steps.
     */
    @SafeVarargs
    static DecisionRule<Double> stepsWithDoubleInterpolation(Step<Double>... steps) {
        List<Step<Double>> sorted = Arrays.stream(steps)
            .sorted((a, b) -> Double.compare(b.healthMin(), a.healthMin()))  // descending
            .toList();
        
        return health -> {
            if (sorted.isEmpty()) {
                return 0.0;
            }
            
            // Find surrounding steps and interpolate
            for (int i = 0; i < sorted.size() - 1; i++) {
                Step<Double> upper = sorted.get(i);
                Step<Double> lower = sorted.get(i + 1);
                
                if (health <= upper.healthMin() && health >= lower.healthMin()) {
                    double ratio = (upper.healthMin() - health) / (upper.healthMin() - lower.healthMin());
                    return upper.value() + (lower.value() - upper.value()) * ratio;
                }
            }
            
            if (health >= sorted.get(0).healthMin()) {
                return sorted.get(0).value();
            }
            return sorted.get(sorted.size() - 1).value();
        };
    }
    
    // ============ Step Record ============
    
    /**
     * A step in the step function.
     */
    record Step<T>(double healthMin, T value) {
        public Step {
            if (healthMin < 0.0 || healthMin > 1.0) {
                throw new IllegalArgumentException("healthMin must be 0.0 - 1.0");
            }
        }
    }
    
    /**
     * Create a step entry.
     */
    static <T> Step<T> step(double healthMin, T value) {
        return new Step<>(healthMin, value);
    }
    
    // ============ Convenience Factories ============
    
    /**
     * Create a constant rule (same value regardless of health).
     */
    static <T> DecisionRule<T> constant(T value) {
        return health -> value;
    }
    
    // ============ Legacy Support (for backward compatibility) ============
    
    /**
     * @deprecated Use {@link #stepsWithInterpolation} instead
     */
    @Deprecated
    static DecisionRule<Duration> linearDuration(Duration min, Duration max) {
        return stepsWithInterpolation(
            step(1.0, min),
            step(0.0, max)
        );
    }
    
    /**
     * @deprecated Use {@link #stepsWithDoubleInterpolation} instead
     */
    @Deprecated
    static DecisionRule<Double> linearRate(double startThreshold, double maxRate) {
        return stepsWithDoubleInterpolation(
            step(startThreshold, 0.0),
            step(0.0, maxRate)
        );
    }
    
    /**
     * @deprecated Use {@link #stepsWithInterpolation} instead
     */
    @Deprecated
    static DecisionRule<Duration> sigmoidDuration(Duration min, Duration max, double steepness) {
        // Approximate sigmoid with steps
        return stepsWithInterpolation(
            step(1.0, min),
            step(0.7, Duration.ofMillis((long) (min.toMillis() + (max.toMillis() - min.toMillis()) * 0.1))),
            step(0.5, Duration.ofMillis((long) (min.toMillis() + (max.toMillis() - min.toMillis()) * 0.5))),
            step(0.3, Duration.ofMillis((long) (min.toMillis() + (max.toMillis() - min.toMillis()) * 0.9))),
            step(0.0, max)
        );
    }
    
    /**
     * @deprecated Use {@link #steps} instead
     */
    @Deprecated
    @SafeVarargs
    static <T> DecisionRule<T> step(Map.Entry<Double, T>... thresholds) {
        @SuppressWarnings("unchecked")
        Step<T>[] steps = Arrays.stream(thresholds)
            .map(e -> new Step<>(e.getKey(), e.getValue()))
            .toArray(Step[]::new);
        return steps(steps);
    }
    
    /**
     * @deprecated Use {@link #step(double, Object)} instead
     */
    @Deprecated
    static <T> Map.Entry<Double, T> entry(double health, T value) {
        return Map.entry(health, value);
    }
}
