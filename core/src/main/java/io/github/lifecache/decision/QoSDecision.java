package io.github.lifecache.decision;

/**
 * Generic QoS decision result from a single rule.
 * 
 * <p>Each rule generates one QoSDecision with its typed result.</p>
 * 
 * <pre>{@code
 * // Define rules
 * DecisionRule<Duration> ttlRule = DecisionRule.stepsWithInterpolation(
 *     DecisionRule.step(1.0, Duration.ofSeconds(30)),
 *     DecisionRule.step(0.0, Duration.ofMinutes(30))
 * );
 * 
 * // Evaluate rule
 * double health = 0.6;
 * QoSDecision<Duration> ttlDecision = ttlRule.evaluate(health);
 * 
 * // Use decision
 * Duration ttl = ttlDecision.value();
 * }</pre>
 *
 * @param <T> the decision value type
 */
public final class QoSDecision<T> {
    
    private final T value;
    
    private QoSDecision(T value) {
        this.value = value;
    }
    
    /**
     * Create from value.
     */
    public static <T> QoSDecision<T> of(T value) {
        return new QoSDecision<>(value);
    }
    
    /**
     * Get the decision value.
     */
    public T value() {
        return value;
    }
    
    @Override
    public String toString() {
        return "QoSDecision{value=" + value + '}';
    }
}
