# 🚢 Lifecache

A quality-aware caching library for Java - like [Lifeboat](https://github.com/zhuweihuacn/lifeboat) keeps your system afloat under pressure, Lifecache dynamically adjusts cache behavior based on real-time system health.

> *"When the seas get rough, Lifecache keeps your cache decisions steady."*

## 🎯 What is Quality-Aware Caching?

Traditional caching uses fixed TTL values. This creates a dilemma:
- **Short TTL** → Fresher data, but more backend load
- **Long TTL** → Less backend load, but stale data

**Lifecache** solves this by dynamically adjusting cache behavior based on real-time system health:

| System State | P95 Latency | Soft TTL | Fallback | Drop Rate |
|--------------|-------------|----------|----------|-----------|
| Healthy      | < 50ms      | 3 min    | NO       | 0%        |
| Degraded     | 200ms       | 17 min   | NO       | 0%        |
| Stressed     | 400ms       | 45 min   | YES      | 0%        |
| Critical     | > 600ms     | 60 min   | YES      | 50%+      |

### 🔄 Self-Balancing Feedback Loop

```
High Load → High Latency → Longer TTL → More Cache Hits → Reduced Backend Load → Latency Drops
                                                                                       ↓
Low Load  ← Low Latency  ← Shorter TTL ← More Cache Misses ← Fresher Data ←←←←←←←←←←←←
```

This creates a **self-stabilizing system** where:
- **Under pressure**: TTL extends (up to 1hr), cache hits increase, backend load decreases
- **When recovered**: TTL shortens (to 3min), data stays fresh, latency remains stable

## 📦 Features

- **Adaptive Staleness** - TTL extends automatically under load
- **Fallback Decisions** - Know when to use degraded data sources
- **Load Shedding** - Probabilistic request dropping when critical
- **Zero Dependencies** - Pure Java, no external libraries
- **Thread-Safe** - Safe for concurrent use
- **One-liner Integration** - Simple builder pattern

## 🚀 Quick Start

### Build
```bash
./gradlew build
```

### Run Demo
```bash
./gradlew :demo:run
```

Output:
```
🚢 ========================================
🚢 Lifecache - Quality-Aware Caching Demo
🚢 ========================================

📈 ========== Lifecache Stats ==========
⏱️  Uptime: 15s | Load: BUSY
─────────────────────────────────────────
📊 Requests: 150 | Hits: 105 | Misses: 45 | Dropped: 0
📉 Hit Rate: 70.0% | Drop Rate: 0.0%
─────────────────────────────────────────
⚡ Latency P50/P95/P99: 175/245/312ms
─────────────────────────────────────────
🎯 Health Score: 0.55 [DEGRADED]
⏰ Staleness: 32m 15s
🔄 Should Fallback: YES ⚠️
🚫 Drop Rate: 0.0%
=========================================
```

## 💻 Usage

### Basic Usage
```java
import io.github.lifecache.Lifecache;
import io.github.lifecache.metrics.MetricsCollector;

// Initialize once at startup
Lifecache lifecache = Lifecache.builder()
    .defaultTtl(Duration.ofMinutes(3))     // TTL when healthy (fresh data)
    .maxTtl(Duration.ofHours(1))           // TTL when critical (reduce load)
    .latencyThreshold(50, 1.0)             // Below 50ms = healthy
    .latencyThreshold(200, 0.5)            // 200ms = degraded
    .latencyThreshold(600, 0.0)            // Above 600ms = critical
    .build();

// Record latency (use try-with-resources)
try (MetricsCollector.Sample sample = lifecache.startSample()) {
    Object result = callBackend();
}

// Get quality-aware decisions
Duration staleness = lifecache.getStaleness();      // For cache TTL
boolean fallback = lifecache.shouldFallback();      // Use backup data?
double dropRate = lifecache.getDropRate();          // Load shedding rate
```

### With Redis
```java
Lifecache lifecache = Lifecache.builder()
    .defaultTtl(Duration.ofMinutes(3))   // Short TTL when healthy
    .maxTtl(Duration.ofHours(1))          // Long TTL when stressed
    .build();

public String getValue(String key) {
    String cached = redis.get(key);
    if (cached != null) {
        return cached;
    }
    
    // Cache miss - fetch with latency tracking
    String value;
    try (MetricsCollector.Sample sample = lifecache.startSample()) {
        value = fetchFromBackend(key);
    }
    
    // Store with adaptive TTL
    Duration ttl = lifecache.getStaleness();
    redis.setex(key, ttl.toSeconds(), value);
    return value;
}
```

### With LocalCache (Adaptive TTL)
```java
import io.github.lifecache.cache.LocalCache;

// Create cache with 1hr max TTL
LocalCache<String> cache = new LocalCache<>(lifecache, Duration.ofHours(1));

// Request flow - TTL adapts based on system health
LocalCache.CacheResult<String> result = cache.get("user:123", () -> {
    // This only runs on cache miss
    return fetchFromBackend("user:123");
});

if (result.isFromCache()) {
    // Cache hit - using cached value (no backend call)
} else {
    // Cache miss - computed fresh value (written to cache with timestamp)
}

// Check current soft TTL
Duration currentTtl = cache.getCurrentSoftTtl();  // Adapts: 1min → 1hr based on health
```

### With Fallback Logic
```java
public Object getData() {
    if (lifecache.shouldFallback()) {
        return getCachedOrDefaultData();  // Use stale/default data
    }
    
    try (MetricsCollector.Sample sample = lifecache.startSample()) {
        return fetchFreshData();
    } catch (Exception e) {
        return getCachedOrDefaultData();
    }
}
```

### With Load Shedding
```java
public Response handleRequest(Request request) {
    if (lifecache.shouldDrop()) {
        return Response.serviceUnavailable("System overloaded");
    }
    
    // Process normally...
}
```

### All Decisions at Once
```java
Lifecache.Decisions decisions = lifecache.getDecisions();

System.out.println("Health: " + decisions.healthScore());
System.out.println("Status: " + decisions.getStatus());
System.out.println("Staleness: " + decisions.staleness());
System.out.println("Fallback: " + decisions.shouldFallback());
System.out.println("Drop Rate: " + decisions.dropRate());
```

## 🎛️ Configuration

### TTL Settings

| Parameter | Recommended | Description |
|-----------|-------------|-------------|
| `defaultTtl` | 3 min | TTL when system is healthy (short = fresh data) |
| `minTtl` | 0 | Minimum allowed TTL |
| `maxTtl` | 60 min | TTL when system is critical (long = reduce load) |

**Example**: With `defaultTtl=3min` and `maxTtl=1hr`, the soft TTL ranges from 3 minutes (healthy) to 1 hour (critical).

### Health Thresholds

| Parameter | Default | Description |
|-----------|---------|-------------|
| `latencyThreshold(ms, score)` | 100→1.0, 500→0.0 | P95 latency to health mapping |
| `fallbackThreshold` | 0.5 | Health below which `shouldFallback()` = true |
| `dropStartThreshold` | 0.3 | Health below which load shedding starts |
| `maxDropRate` | 0.9 | Maximum drop rate (always let 10% through) |

### Health Policies

**Step Function (Default)**
```java
.latencyThreshold(100, 1.0)
.latencyThreshold(200, 0.75)
.latencyThreshold(300, 0.5)
.latencyThreshold(400, 0.25)
```

## 📊 Monitoring

```java
Lifecache.Metrics metrics = lifecache.getMetrics();

metrics.p50Latency();    // Median latency
metrics.p95Latency();    // 95th percentile
metrics.p99Latency();    // 99th percentile
metrics.sampleCount();   // Samples in window
metrics.healthScore();   // Current health (0-1)
metrics.staleness();     // Current adaptive TTL
metrics.getStatus();     // HEALTHY/DEGRADED/STRESSED/CRITICAL
```

## 🏗️ Project Structure

```
lifecache/
├── core/                           # Core library
│   └── src/main/java/io/github/lifecache/
│       ├── Lifecache.java          # Main facade
│       ├── cache/
│       │   ├── LocalCache.java     # Adaptive TTL local cache
│       │   └── CacheEntry.java     # Cache entry with timestamp
│       ├── metrics/
│       │   ├── MetricsCollector.java
│       │   ├── MetricsSnapshot.java
│       │   └── SlidingWindowCollector.java
│       └── policy/
│           ├── HealthPolicy.java
│           └── StepFunctionPolicy.java
├── demo/                           # Demo application
│   └── ConcurrencyLatencySimulator.java  # Realistic latency simulation
└── README.md
```

## 🔗 Related Projects

- [Lifeboat](https://github.com/zhuweihuacn/lifeboat) - Load shedding library for Java

---

**Group ID**: `io.github.lifecache`  
**Version**: `1.0.0`  
**Java**: 17+
