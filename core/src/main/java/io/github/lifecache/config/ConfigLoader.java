package io.github.lifecache.config;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;

/**
 * Simple JSON config loader without external dependencies.
 * Parses JSON into config records.
 * 
 * <p>Usage:</p>
 * <pre>{@code
 * // From JSON string
 * AdaptiveQoSConfig config = ConfigLoader.fromJson(jsonString);
 * 
 * // From file
 * AdaptiveQoSConfig config = ConfigLoader.fromFile(path);
 * 
 * // To JSON
 * String json = ConfigLoader.toJson(config);
 * }</pre>
 */
public class ConfigLoader {
    
    /**
     * Load config from JSON string.
     */
    public static AdaptiveQoSConfig fromJson(String json) {
        Map<String, Object> map = parseJson(json);
        return mapToConfig(map);
    }
    
    /**
     * Load config from file.
     */
    public static AdaptiveQoSConfig fromFile(Path path) throws IOException {
        String json = Files.readString(path);
        return fromJson(json);
    }
    
    /**
     * Load config from file path string.
     */
    public static AdaptiveQoSConfig fromFile(String path) throws IOException {
        return fromFile(Path.of(path));
    }
    
    /**
     * Load config from input stream.
     */
    public static AdaptiveQoSConfig fromStream(InputStream stream) throws IOException {
        String json = new String(stream.readAllBytes());
        return fromJson(json);
    }
    
    /**
     * Load config from classpath resource.
     */
    public static AdaptiveQoSConfig fromResource(String resourcePath) throws IOException {
        try (InputStream stream = ConfigLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            return fromStream(stream);
        }
    }
    
    /**
     * Convert config to JSON string.
     */
    public static String toJson(AdaptiveQoSConfig config) {
        return toJson(config, 0);
    }
    
    /**
     * Convert config to pretty-printed JSON string.
     */
    public static String toJsonPretty(AdaptiveQoSConfig config) {
        return toJsonPretty(configToMap(config), 0);
    }
    
    // ============ JSON Parsing (Simple Implementation) ============
    
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJson(String json) {
        json = json.trim();
        if (!json.startsWith("{")) {
            throw new IllegalArgumentException("JSON must start with {");
        }
        return (Map<String, Object>) parseValue(new JsonReader(json));
    }
    
    private static Object parseValue(JsonReader reader) {
        reader.skipWhitespace();
        char c = reader.peek();
        
        if (c == '{') {
            return parseObject(reader);
        } else if (c == '[') {
            return parseArray(reader);
        } else if (c == '"') {
            return parseString(reader);
        } else if (c == 't' || c == 'f') {
            return parseBoolean(reader);
        } else if (c == 'n') {
            return parseNull(reader);
        } else if (Character.isDigit(c) || c == '-') {
            return parseNumber(reader);
        }
        
        throw new IllegalArgumentException("Unexpected character: " + c + " at position " + reader.pos);
    }
    
    private static Map<String, Object> parseObject(JsonReader reader) {
        Map<String, Object> map = new LinkedHashMap<>();
        reader.expect('{');
        reader.skipWhitespace();
        
        if (reader.peek() == '}') {
            reader.expect('}');
            return map;
        }
        
        while (true) {
            reader.skipWhitespace();
            String key = parseString(reader);
            reader.skipWhitespace();
            reader.expect(':');
            Object value = parseValue(reader);
            map.put(key, value);
            
            reader.skipWhitespace();
            if (reader.peek() == '}') {
                reader.expect('}');
                break;
            }
            reader.expect(',');
        }
        
        return map;
    }
    
    private static List<Object> parseArray(JsonReader reader) {
        List<Object> list = new ArrayList<>();
        reader.expect('[');
        reader.skipWhitespace();
        
        if (reader.peek() == ']') {
            reader.expect(']');
            return list;
        }
        
        while (true) {
            list.add(parseValue(reader));
            reader.skipWhitespace();
            if (reader.peek() == ']') {
                reader.expect(']');
                break;
            }
            reader.expect(',');
        }
        
        return list;
    }
    
    private static String parseString(JsonReader reader) {
        reader.expect('"');
        StringBuilder sb = new StringBuilder();
        while (reader.peek() != '"') {
            char c = reader.next();
            if (c == '\\') {
                char escaped = reader.next();
                switch (escaped) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(escaped);
                }
            } else {
                sb.append(c);
            }
        }
        reader.expect('"');
        return sb.toString();
    }
    
    private static Number parseNumber(JsonReader reader) {
        StringBuilder sb = new StringBuilder();
        boolean isDouble = false;
        
        if (reader.peek() == '-') {
            sb.append(reader.next());
        }
        
        while (reader.hasMore()) {
            char c = reader.peek();
            if (Character.isDigit(c)) {
                sb.append(reader.next());
            } else if (c == '.' || c == 'e' || c == 'E') {
                isDouble = true;
                sb.append(reader.next());
            } else if (c == '+' || c == '-') {
                sb.append(reader.next());
            } else {
                break;
            }
        }
        
        String numStr = sb.toString();
        if (isDouble) {
            return Double.parseDouble(numStr);
        } else {
            long val = Long.parseLong(numStr);
            if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) {
                return (int) val;
            }
            return val;
        }
    }
    
    private static Boolean parseBoolean(JsonReader reader) {
        if (reader.peek() == 't') {
            reader.expect('t');
            reader.expect('r');
            reader.expect('u');
            reader.expect('e');
            return true;
        } else {
            reader.expect('f');
            reader.expect('a');
            reader.expect('l');
            reader.expect('s');
            reader.expect('e');
            return false;
        }
    }
    
    private static Object parseNull(JsonReader reader) {
        reader.expect('n');
        reader.expect('u');
        reader.expect('l');
        reader.expect('l');
        return null;
    }
    
    private static class JsonReader {
        private final String json;
        private int pos = 0;
        
        JsonReader(String json) {
            this.json = json;
        }
        
        char peek() {
            return json.charAt(pos);
        }
        
        char next() {
            return json.charAt(pos++);
        }
        
        void expect(char c) {
            if (next() != c) {
                throw new IllegalArgumentException("Expected '" + c + "' at position " + (pos - 1));
            }
        }
        
        void skipWhitespace() {
            while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) {
                pos++;
            }
        }
        
        boolean hasMore() {
            return pos < json.length();
        }
    }
    
    // ============ Map to Config Conversion ============
    
    @SuppressWarnings("unchecked")
    private static AdaptiveQoSConfig mapToConfig(Map<String, Object> map) {
        // Support both "registry" and legacy "collector"
        Map<String, Object> registryMap = (Map<String, Object>) map.get("registry");
        if (registryMap == null) {
            registryMap = (Map<String, Object>) map.get("collector");
        }
        return new AdaptiveQoSConfig(
            parseRegistryConfig(registryMap),
            parseEvaluatorConfig((Map<String, Object>) map.get("evaluator")),
            parseDecisionsConfig((Map<String, Object>) map.get("decisions")),
            parseDerivedMetricsConfig((Map<String, Object>) map.get("derivedMetrics")),
            (Map<String, Object>) map.getOrDefault("metadata", Map.of())
        );
    }
    
    @SuppressWarnings("unchecked")
    private static AdaptiveQoSConfig.RegistryConfig parseRegistryConfig(Map<String, Object> map) {
        if (map == null) return AdaptiveQoSConfig.RegistryConfig.defaults();
        
        List<AdaptiveQoSConfig.SignalStoreConfig> signalStores = new ArrayList<>();
        // Support both "signalStores" and legacy "keyStores" 
        List<Map<String, Object>> storeList = (List<Map<String, Object>>) map.get("signalStores");
        if (storeList == null) {
            storeList = (List<Map<String, Object>>) map.get("keyStores");
        }
        if (storeList != null) {
            for (Map<String, Object> s : storeList) {
                signalStores.add(parseSignalStoreConfig(s));
            }
        }
        
        return new AdaptiveQoSConfig.RegistryConfig(
            (String) map.getOrDefault("type", "ROUTED"),
            signalStores
        );
    }
    
    @SuppressWarnings("unchecked")
    private static AdaptiveQoSConfig.SignalStoreConfig parseSignalStoreConfig(Map<String, Object> map) {
        // Parse bucketSeconds and maxBuckets, with backward compatibility for windowSeconds
        int bucketSeconds = getInt(map, "bucketSeconds", 0);
        int maxBuckets = getInt(map, "maxBuckets", 0);
        
        // Backward compatibility: convert windowSeconds to bucket config
        if (bucketSeconds == 0 && map.containsKey("windowSeconds")) {
            int windowSeconds = getInt(map, "windowSeconds", 60);
            bucketSeconds = 10;
            maxBuckets = windowSeconds / 10;
        }
        if (bucketSeconds <= 0) bucketSeconds = 10;
        if (maxBuckets <= 0) maxBuckets = 6;
        
        // Get aggregations (support both "aggregations" and legacy "percentiles")
        List<String> aggregations = (List<String>) map.get("aggregations");
        if (aggregations == null) {
            aggregations = (List<String>) map.get("percentiles");
        }
        if (aggregations == null) {
            aggregations = List.of("P95");
        }
        
        // Parse per-signal processor config
        AdaptiveQoSConfig.ProcessorConfig processor = parseProcessorConfig(
            (Map<String, Object>) map.get("processor"));
        
        return new AdaptiveQoSConfig.SignalStoreConfig(
            (String) map.get("name"),
            (String) map.getOrDefault("type", "GAUGE"),
            bucketSeconds,
            maxBuckets,
            aggregations,
            processor
        );
    }
    
    private static AdaptiveQoSConfig.ProcessorConfig parseProcessorConfig(Map<String, Object> map) {
        if (map == null) return AdaptiveQoSConfig.ProcessorConfig.defaults();
        
        return new AdaptiveQoSConfig.ProcessorConfig(
            getBool(map, "dropNegative", true),  // Default: drop negative
            getDouble(map, "maxValue"),
            getDouble(map, "minValue")
        );
    }
    
    @SuppressWarnings("unchecked")
    private static AdaptiveQoSConfig.EvaluatorConfig parseEvaluatorConfig(Map<String, Object> map) {
        if (map == null) return AdaptiveQoSConfig.EvaluatorConfig.defaults();
        
        List<AdaptiveQoSConfig.RuleConfig> rules = new ArrayList<>();
        List<Map<String, Object>> ruleList = (List<Map<String, Object>>) map.get("rules");
        if (ruleList != null) {
            for (Map<String, Object> r : ruleList) {
                boolean isDerived = getBool(r, "isDerived", false);
                rules.add(new AdaptiveQoSConfig.RuleConfig(
                    (String) r.get("metricName"),
                    isDerived ? null : (String) r.getOrDefault("percentile", "P95"),
                    isDerived ? 0 : getIntOrDefault(r, "windowSeconds", 30),
                    getDoubleOrDefault(r, "weight", 1.0),
                    isDerived,
                    parseStepFunctionConfig((Map<String, Object>) r.get("stepFunction"))
                ));
            }
        }
        
        return new AdaptiveQoSConfig.EvaluatorConfig(
            rules,
            (String) map.getOrDefault("aggregation", "WEIGHTED_AVG")
        );
    }
    
    @SuppressWarnings("unchecked")
    private static AdaptiveQoSConfig.StepFunctionConfig parseStepFunctionConfig(Map<String, Object> map) {
        if (map == null) return AdaptiveQoSConfig.StepFunctionConfig.defaultLatency();
        
        List<AdaptiveQoSConfig.ThresholdConfig> thresholds = new ArrayList<>();
        List<Map<String, Object>> thresholdList = (List<Map<String, Object>>) map.get("thresholds");
        if (thresholdList != null) {
            for (Map<String, Object> t : thresholdList) {
                thresholds.add(new AdaptiveQoSConfig.ThresholdConfig(
                    getDoubleOrDefault(t, "value", 0),
                    getDoubleOrDefault(t, "score", 1.0)
                ));
            }
        }
        
        return new AdaptiveQoSConfig.StepFunctionConfig(
            thresholds,
            (String) map.getOrDefault("interpolation", "LINEAR")
        );
    }
    
    @SuppressWarnings("unchecked")
    private static Map<String, AdaptiveQoSConfig.DecisionConfig> parseDecisionsConfig(Map<String, Object> map) {
        if (map == null) return AdaptiveQoSConfig.DecisionConfig.defaults();
        
        Map<String, AdaptiveQoSConfig.DecisionConfig> decisions = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Map<String, Object> decisionMap = (Map<String, Object>) entry.getValue();
            decisions.put(entry.getKey(), parseDecisionConfig(decisionMap));
        }
        return decisions;
    }
    
    @SuppressWarnings("unchecked")
    private static AdaptiveQoSConfig.DecisionConfig parseDecisionConfig(Map<String, Object> map) {
        if (map == null) {
            return new AdaptiveQoSConfig.DecisionConfig("DOUBLE", null, "STEP");
        }
        
        // Parse steps (can be at top level or in "function" for backward compatibility)
        List<AdaptiveQoSConfig.StepConfig> steps = new ArrayList<>();
        List<Map<String, Object>> stepList = (List<Map<String, Object>>) map.get("steps");
        
        // Backward compatibility: check in "function" sub-object
        if (stepList == null) {
            Map<String, Object> funcMap = (Map<String, Object>) map.get("function");
            if (funcMap != null) {
                stepList = (List<Map<String, Object>>) funcMap.get("steps");
            }
        }
        
        if (stepList != null) {
            for (Map<String, Object> s : stepList) {
                steps.add(new AdaptiveQoSConfig.StepConfig(
                    getDoubleOrDefault(s, "healthMin", 0),
                    s.get("value")
                ));
            }
        }
        
        return new AdaptiveQoSConfig.DecisionConfig(
            (String) map.getOrDefault("type", "DOUBLE"),
            steps.isEmpty() ? null : steps,
            (String) map.getOrDefault("interpolation", "STEP")
        );
    }
    
    @SuppressWarnings("unchecked")
    private static Map<String, AdaptiveQoSConfig.DerivedMetricConfig> parseDerivedMetricsConfig(Map<String, Object> map) {
        if (map == null) return Map.of();
        
        Map<String, AdaptiveQoSConfig.DerivedMetricConfig> derivedMetrics = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Map<String, Object> metricMap = (Map<String, Object>) entry.getValue();
            derivedMetrics.put(entry.getKey(), new AdaptiveQoSConfig.DerivedMetricConfig(
                (String) metricMap.get("formula"),
                (String) metricMap.getOrDefault("aggregation", "SUM"),
                getIntOrDefault(metricMap, "windowSeconds", 300)
            ));
        }
        return derivedMetrics;
    }
    
    // ============ Config to Map (for JSON output) ============
    
    private static Map<String, Object> configToMap(AdaptiveQoSConfig config) {
        Map<String, Object> map = new LinkedHashMap<>();
        // TODO: Implement full serialization
        map.put("collector", Map.of("type", config.collector().type()));
        map.put("evaluator", Map.of("aggregation", config.evaluator().aggregation()));
        map.put("metadata", config.metadata());
        return map;
    }
    
    private static String toJson(Object obj, int indent) {
        // Simple JSON serialization for now
        return obj.toString();
    }
    
    private static String toJsonPretty(Map<String, Object> map, int indent) {
        StringBuilder sb = new StringBuilder();
        String pad = "  ".repeat(indent);
        String pad1 = "  ".repeat(indent + 1);
        
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            sb.append(pad1).append("\"").append(entry.getKey()).append("\": ");
            sb.append(valueToJson(entry.getValue(), indent + 1));
            if (++i < map.size()) sb.append(",");
            sb.append("\n");
        }
        sb.append(pad).append("}");
        return sb.toString();
    }
    
    @SuppressWarnings("unchecked")
    private static String valueToJson(Object value, int indent) {
        if (value == null) return "null";
        if (value instanceof String) return "\"" + value + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map) return toJsonPretty((Map<String, Object>) value, indent);
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            if (list.isEmpty()) return "[]";
            StringBuilder sb = new StringBuilder();
            sb.append("[\n");
            String pad = "  ".repeat(indent + 1);
            for (int i = 0; i < list.size(); i++) {
                sb.append(pad).append(valueToJson(list.get(i), indent + 1));
                if (i < list.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("  ".repeat(indent)).append("]");
            return sb.toString();
        }
        return "\"" + value + "\"";
    }
    
    // ============ Helpers ============
    
    private static int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        return defaultValue;
    }
    
    private static double getDoubleOrDefault(Map<String, Object> map, String key, double defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        return defaultValue;
    }
    
    private static int getIntOrDefault(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        return defaultValue;
    }
    
    private static Double getDouble(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        return null;
    }
    
    private static Long getLong(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).longValue();
        return null;
    }
    
    private static boolean getBool(Map<String, Object> map, String key, boolean defaultValue) {
        Object val = map.get(key);
        if (val instanceof Boolean) return (Boolean) val;
        return defaultValue;
    }
}
