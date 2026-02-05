package io.github.lifecache.policy;

import io.github.lifecache.metrics.Aggregation;
import io.github.lifecache.metrics.MetricsReader;

import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Step function evaluator with configurable thresholds.
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
 * 
 * <p>Usage:</p>
 * <pre>{@code
 * QoSEvaluator evaluator = StepFunctionEvaluator.builder()
 *     .metricsReader(registry)
 *     .metricName("latency")
 *     .aggregation(Aggregation.P95)
 *     .window(Duration.ofSeconds(30))
 *     .threshold(100, 1.0)
 *     .threshold(300, 0.5)
 *     .threshold(500, 0.0)
 *     .build();
 * 
 * double health = evaluator.evaluate();
 * }</pre>
 */
public class StepFunctionEvaluator implements QoSEvaluator {
    
    private final MetricsReader metricsReader;
    private final String metricName;
    private final Aggregation aggregation;
    private final Duration window;
    private final double[][] thresholds;
    
    private StepFunctionEvaluator(MetricsReader metricsReader, 
                                   String metricName,
                                   Aggregation aggregation,
                                   Duration window,
                                   double[][] thresholds) {
        this.metricsReader = metricsReader;
        this.metricName = metricName;
        this.aggregation = aggregation;
        this.window = window;
        this.thresholds = Arrays.stream(thresholds)
            .sorted(Comparator.comparingDouble(a -> a[0]))
            .toArray(double[][]::new);
    }
    
    @Override
    public double evaluate() {
        Double value = metricsReader.read(metricName, aggregation, window);
        if (value == null) {
            return 1.0;  // No data = assume healthy
        }
        return evaluateValue(value);
    }
    
    /**
     * Evaluate health from a single value.
     */
    public double evaluateValue(double value) {
        if (thresholds.length == 0) {
            return 1.0;
        }
        
        if (value <= thresholds[0][0]) {
            return thresholds[0][1];
        }
        
        if (value >= thresholds[thresholds.length - 1][0]) {
            return thresholds[thresholds.length - 1][1];
        }
        
        // Linear interpolation between thresholds
        for (int i = 1; i < thresholds.length; i++) {
            if (value <= thresholds[i][0]) {
                double[] prev = thresholds[i - 1];
                double[] curr = thresholds[i];
                
                double ratio = (value - prev[0]) / (curr[0] - prev[0]);
                return prev[1] + (curr[1] - prev[1]) * ratio;
            }
        }
        
        return thresholds[thresholds.length - 1][1];
    }
    
    // ============ Builder ============
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private MetricsReader metricsReader;
        private String metricName;
        private Aggregation aggregation = Aggregation.P95;
        private Duration window = Duration.ofSeconds(30);
        private java.util.List<double[]> thresholdList = new java.util.ArrayList<>();
        
        public Builder metricsReader(MetricsReader reader) {
            this.metricsReader = reader;
            return this;
        }
        
        public Builder metricName(String name) {
            this.metricName = name;
            return this;
        }
        
        public Builder aggregation(Aggregation agg) {
            this.aggregation = agg;
            return this;
        }
        
        public Builder window(Duration window) {
            this.window = window;
            return this;
        }
        
        public Builder threshold(double value, double healthScore) {
            thresholdList.add(new double[]{value, healthScore});
            return this;
        }
        
        public Builder thresholds(double[][] thresholds) {
            for (double[] t : thresholds) {
                thresholdList.add(t);
            }
            return this;
        }
        
        public StepFunctionEvaluator build() {
            if (metricsReader == null) {
                throw new IllegalStateException("MetricsReader is required");
            }
            if (metricName == null) {
                throw new IllegalStateException("MetricName is required");
            }
            if (thresholdList.isEmpty()) {
                throw new IllegalStateException("At least one threshold is required");
            }
            
            double[][] arr = thresholdList.toArray(new double[0][]);
            return new StepFunctionEvaluator(metricsReader, metricName, aggregation, window, arr);
        }
    }
}
