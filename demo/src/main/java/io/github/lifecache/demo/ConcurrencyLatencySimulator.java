package io.github.lifecache.demo;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulates realistic latency based on current concurrency levels.
 * 
 * <p>Inspired by <a href="https://github.com/zhuweihuacn/lifeboat">Lifeboat</a>'s latency simulator.</p>
 * 
 * <p>Latency increases as concurrency grows, modeling real-world contention:</p>
 * <ul>
 *   <li>Concurrency 0-4:   baseline latency (50ms)</li>
 *   <li>Concurrency 5-10:  50ms P95</li>
 *   <li>Concurrency 10-20: gradually increases to 100ms P95</li>
 *   <li>Concurrency 20-30: gradually increases to 200ms P95</li>
 *   <li>Concurrency 30+:   400ms P95</li>
 * </ul>
 */
public class ConcurrencyLatencySimulator {
    
    private final Random random = new Random();
    private final AtomicInteger currentConcurrency = new AtomicInteger(0);
    
    // Latency thresholds (in ms)
    private static final int BASELINE_MIN = 50;
    private static final int BASELINE_MAX = 50;
    private static final int TIER1_P95 = 50;      // concurrency 5-10
    private static final int TIER2_P95 = 100;     // concurrency 10-20
    private static final int TIER3_P95 = 200;     // concurrency 20-30
    private static final int TIER4_P95 = 400;     // concurrency 30+
    
    /**
     * Execute a task with simulated latency based on current concurrency.
     * Automatically tracks concurrency for you.
     */
    public void executeWithLatency(Runnable task) throws InterruptedException {
        int concurrency = currentConcurrency.incrementAndGet();
        try {
            long sleepTime = calculateLatency(concurrency);
            Thread.sleep(sleepTime);
            task.run();
        } finally {
            currentConcurrency.decrementAndGet();
        }
    }
    
    /**
     * Calculate sleep time based on concurrency level.
     * Uses a probabilistic model to create realistic P95 distributions.
     */
    public long calculateLatency(int concurrency) {
        if (concurrency < 5) {
            // Baseline: simple uniform distribution
            return BASELINE_MIN + random.nextInt(BASELINE_MAX - BASELINE_MIN + 1);
        } else if (concurrency < 10) {
            // Tier 1: 50ms P50, with variance
            return calculateTieredLatency(TIER1_P95, 0.5);
        } else if (concurrency < 20) {
            // Tier 2: gradually increase from 50ms to 100ms P95
            double progress = (concurrency - 10) / 10.0; // 0.0 to 1.0
            int targetP95 = (int) lerp(TIER1_P95, TIER2_P95, progress);
            return calculateTieredLatency(targetP95, 0.95);
        } else if (concurrency < 30) {
            // Tier 3: gradually increase from 100ms to 200ms P95
            double progress = (concurrency - 20) / 10.0; // 0.0 to 1.0
            int targetP95 = (int) lerp(TIER2_P95, TIER3_P95, progress);
            return calculateTieredLatency(targetP95, 0.95);
        } else {
            // Tier 4: 400ms P95
            return calculateTieredLatency(TIER4_P95, 0.95);
        }
    }
    
    /**
     * Calculate latency using a log-normal distribution to model realistic P95.
     * 
     * @param targetLatency The target latency at the given percentile
     * @param percentile The percentile (e.g., 0.95 for P95)
     * @return Simulated latency in ms
     */
    private long calculateTieredLatency(int targetLatency, double percentile) {
        // Use log-normal distribution for realistic latency modeling
        // P95 means 95% of values are below this, 5% are above
        
        // Generate a value from 0 to 1 with a distribution skewed toward lower values
        double u = random.nextDouble();
        
        // Transform to create a distribution where:
        // - Most values (percentile%) are below targetLatency
        // - Some values (1-percentile%) exceed it
        
        if (u < percentile) {
            // Below the percentile: use a beta-like distribution
            // This creates values mostly in the lower range
            double normalized = u / percentile;
            double shaped = Math.pow(normalized, 0.5); // Skew toward middle-low
            return Math.round(targetLatency * 0.3 + targetLatency * 0.7 * shaped);
        } else {
            // Above the percentile: tail latency (5% of requests)
            double excess = (u - percentile) / (1.0 - percentile);
            // Tail can be 1x to 2x the target latency
            return Math.round(targetLatency * (1.0 + excess));
        }
    }
    
    /**
     * Linear interpolation between two values.
     */
    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
    
    /**
     * Get current concurrency level.
     */
    public int getCurrentConcurrency() {
        return currentConcurrency.get();
    }
    
    /**
     * Manually increment concurrency (for external tracking).
     */
    public int incrementConcurrency() {
        return currentConcurrency.incrementAndGet();
    }
    
    /**
     * Manually decrement concurrency (for external tracking).
     */
    public int decrementConcurrency() {
        return currentConcurrency.decrementAndGet();
    }
    
    /**
     * Sleep based on current concurrency. 
     * Call incrementConcurrency() before and decrementConcurrency() after.
     */
    public void sleep() throws InterruptedException {
        long sleepTime = calculateLatency(currentConcurrency.get());
        Thread.sleep(sleepTime);
    }
    
    /**
     * Sleep based on provided concurrency level (for external concurrency tracking).
     */
    public void sleep(int concurrency) throws InterruptedException {
        long sleepTime = calculateLatency(concurrency);
        Thread.sleep(sleepTime);
    }
}
