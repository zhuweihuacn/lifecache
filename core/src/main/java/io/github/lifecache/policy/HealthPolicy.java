package io.github.lifecache.policy;

import io.github.lifecache.metrics.MetricsCollector;

/**
 * Policy for computing system health score from metrics.
 * 
 * <ul>
 *   <li>1.0 = healthy, use default behavior</li>
 *   <li>0.5 = degraded, start extending TTL/enabling fallback</li>
 *   <li>0.0 = critical, max TTL, aggressive load shedding</li>
 * </ul>
 */
public interface HealthPolicy {
    
    /**
     * Evaluate current system health.
     * 
     * @param collector metrics collector with current samples
     * @return health score between 0.0 (critical) and 1.0 (healthy)
     */
    double evaluate(MetricsCollector collector);
}
