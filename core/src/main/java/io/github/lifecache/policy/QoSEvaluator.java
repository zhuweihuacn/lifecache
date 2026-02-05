package io.github.lifecache.policy;

/**
 * QoS evaluator - returns health score from 0.0 (critical) to 1.0 (healthy).
 * 
 * <p>The evaluator is stateful and holds a reference to the metrics source internally,
 * so evaluate() takes no parameters.</p>
 * 
 * <ul>
 *   <li>1.0 = healthy, use default behavior</li>
 *   <li>0.5 = degraded, start extending TTL</li>
 *   <li>0.0 = critical, max TTL, aggressive throttling</li>
 * </ul>
 */
@FunctionalInterface
public interface QoSEvaluator {
    
    /**
     * Evaluate current system health.
     *
     * @return health score between 0.0 (critical) and 1.0 (healthy)
     */
    double evaluate();
}
