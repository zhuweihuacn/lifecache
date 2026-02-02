package io.github.lifecache.policy;

import io.github.lifecache.metrics.MetricsCollector;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Step function policy with configurable thresholds.
 * Uses linear interpolation between thresholds for smooth transitions.
 * 
 * <p>Example thresholds:</p>
 * <pre>
 * (100ms, 1.0)  - Below 100ms = fully healthy
 * (200ms, 0.75) - 100-200ms = slightly degraded
 * (300ms, 0.5)  - 200-300ms = degraded  
 * (400ms, 0.25) - 300-400ms = stressed
 * (500ms, 0.0)  - Above 500ms = critical
 * </pre>
 */
public class StepFunctionPolicy implements HealthPolicy {
    
    private final double[][] thresholds;
    
    /**
     * Create with default thresholds
     */
    public StepFunctionPolicy() {
        this(new double[][]{
            {100, 1.0},
            {200, 0.75},
            {300, 0.5},
            {400, 0.25},
            {500, 0.0}
        });
    }
    
    /**
     * Create with custom thresholds.
     * 
     * @param thresholds array of [latencyMs, healthScore] pairs
     */
    public StepFunctionPolicy(double[][] thresholds) {
        this.thresholds = Arrays.stream(thresholds)
            .sorted(Comparator.comparingDouble(a -> a[0]))
            .toArray(double[][]::new);
    }
    
    @Override
    public double evaluate(MetricsCollector collector) {
        if (collector.getSampleCount() == 0) {
            return 1.0;
        }
        
        double p95 = collector.getP95();
        
        if (p95 <= thresholds[0][0]) {
            return thresholds[0][1];
        }
        
        if (p95 >= thresholds[thresholds.length - 1][0]) {
            return thresholds[thresholds.length - 1][1];
        }
        
        for (int i = 1; i < thresholds.length; i++) {
            if (p95 <= thresholds[i][0]) {
                double[] prev = thresholds[i - 1];
                double[] curr = thresholds[i];
                
                double ratio = (p95 - prev[0]) / (curr[0] - prev[0]);
                return prev[1] + (curr[1] - prev[1]) * ratio;
            }
        }
        
        return thresholds[thresholds.length - 1][1];
    }
}
