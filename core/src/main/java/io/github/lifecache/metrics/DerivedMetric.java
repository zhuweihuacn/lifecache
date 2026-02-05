package io.github.lifecache.metrics;

import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derived metric computed from other metrics using expressions.
 *
 * <p>Supports formulas like:</p>
 * <ul>
 *   <li>{@code error_count / (error_count + success_count)} - error rate</li>
 *   <li>{@code cache_hits / (cache_hits + cache_misses)} - hit rate</li>
 *   <li>{@code bytes_sent / request_count} - avg bytes per request</li>
 * </ul>
 *
 * <pre>{@code
 * DerivedMetric errorRate = DerivedMetric.builder()
 *     .name("error_rate")
 *     .formula("error_count / (error_count + success_count)")
 *     .aggregation(Aggregation.SUM)
 *     .window(Duration.ofMinutes(5))
 *     .build();
 *
 * Double rate = errorRate.compute(metricsReader);
 * }</pre>
 */
public class DerivedMetric {
    
    private final String name;
    private final String formula;
    private final Aggregation aggregation;
    private final Duration window;
    private final Expression parsedExpression;
    
    private DerivedMetric(String name, String formula, Aggregation aggregation, Duration window) {
        this.name = name;
        this.formula = formula;
        this.aggregation = aggregation;
        this.window = window;
        this.parsedExpression = ExpressionParser.parse(formula);
    }
    
    public String name() { return name; }
    public String formula() { return formula; }
    public Aggregation aggregation() { return aggregation; }
    public Duration window() { return window; }
    
    /**
     * Compute the derived metric value.
     *
     * @param reader metrics reader to fetch base metrics
     * @return computed value, or null if any required metric is unavailable
     */
    public Double compute(MetricsReader reader) {
        return parsedExpression.evaluate(metricName -> 
            reader.read(metricName, aggregation, window)
        );
    }
    
    /**
     * Get all metric names referenced in the formula.
     */
    public Set<String> getReferencedMetrics() {
        return parsedExpression.getMetricNames();
    }
    
    // ============ Builder ============
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private String name;
        private String formula;
        private Aggregation aggregation = Aggregation.SUM;
        private Duration window = Duration.ofMinutes(5);
        
        public Builder name(String name) {
            this.name = name;
            return this;
        }
        
        public Builder formula(String formula) {
            this.formula = formula;
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
        
        public DerivedMetric build() {
            Objects.requireNonNull(name, "name is required");
            Objects.requireNonNull(formula, "formula is required");
            return new DerivedMetric(name, formula, aggregation, window);
        }
    }
    
    // ============ Expression Parser ============
    
    /**
     * Simple expression evaluator supporting +, -, *, / and parentheses.
     */
    interface Expression {
        Double evaluate(java.util.function.Function<String, Double> metricResolver);
        Set<String> getMetricNames();
    }
    
    static class ExpressionParser {
        private static final Pattern METRIC_PATTERN = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");
        
        static Expression parse(String formula) {
            return new Parser(formula).parse();
        }
        
        private static class Parser {
            private final String input;
            private int pos = 0;
            
            Parser(String input) {
                this.input = input.replaceAll("\\s+", "");
            }
            
            Expression parse() {
                Expression result = parseAddSub();
                if (pos < input.length()) {
                    throw new IllegalArgumentException("Unexpected character at position " + pos + ": " + input.charAt(pos));
                }
                return result;
            }
            
            // Addition and subtraction (lowest precedence)
            private Expression parseAddSub() {
                Expression left = parseMulDiv();
                while (pos < input.length()) {
                    char op = input.charAt(pos);
                    if (op == '+') {
                        pos++;
                        Expression right = parseMulDiv();
                        final Expression l = left;
                        left = new BinaryOp(l, right, '+');
                    } else if (op == '-') {
                        pos++;
                        Expression right = parseMulDiv();
                        final Expression l = left;
                        left = new BinaryOp(l, right, '-');
                    } else {
                        break;
                    }
                }
                return left;
            }
            
            // Multiplication and division (higher precedence)
            private Expression parseMulDiv() {
                Expression left = parsePrimary();
                while (pos < input.length()) {
                    char op = input.charAt(pos);
                    if (op == '*') {
                        pos++;
                        Expression right = parsePrimary();
                        final Expression l = left;
                        left = new BinaryOp(l, right, '*');
                    } else if (op == '/') {
                        pos++;
                        Expression right = parsePrimary();
                        final Expression l = left;
                        left = new BinaryOp(l, right, '/');
                    } else {
                        break;
                    }
                }
                return left;
            }
            
            // Primary: number, metric name, or parenthesized expression
            private Expression parsePrimary() {
                if (pos >= input.length()) {
                    throw new IllegalArgumentException("Unexpected end of expression");
                }
                
                char c = input.charAt(pos);
                
                // Parenthesized expression
                if (c == '(') {
                    pos++;
                    Expression expr = parseAddSub();
                    if (pos >= input.length() || input.charAt(pos) != ')') {
                        throw new IllegalArgumentException("Missing closing parenthesis");
                    }
                    pos++;
                    return expr;
                }
                
                // Number (including decimals)
                if (Character.isDigit(c) || c == '.') {
                    return parseNumber();
                }
                
                // Metric name
                if (Character.isLetter(c) || c == '_') {
                    return parseMetricName();
                }
                
                throw new IllegalArgumentException("Unexpected character: " + c);
            }
            
            private Expression parseNumber() {
                int start = pos;
                while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
                    pos++;
                }
                double value = Double.parseDouble(input.substring(start, pos));
                return new Constant(value);
            }
            
            private Expression parseMetricName() {
                int start = pos;
                while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) {
                    pos++;
                }
                String name = input.substring(start, pos);
                return new MetricRef(name);
            }
        }
        
        // Expression nodes
        private record Constant(double value) implements Expression {
            @Override
            public Double evaluate(java.util.function.Function<String, Double> resolver) {
                return value;
            }
            
            @Override
            public Set<String> getMetricNames() {
                return Set.of();
            }
        }
        
        private record MetricRef(String name) implements Expression {
            @Override
            public Double evaluate(java.util.function.Function<String, Double> resolver) {
                return resolver.apply(name);
            }
            
            @Override
            public Set<String> getMetricNames() {
                return Set.of(name);
            }
        }
        
        private record BinaryOp(Expression left, Expression right, char op) implements Expression {
            @Override
            public Double evaluate(java.util.function.Function<String, Double> resolver) {
                Double l = left.evaluate(resolver);
                Double r = right.evaluate(resolver);
                
                if (l == null || r == null) {
                    return null;
                }
                
                return switch (op) {
                    case '+' -> l + r;
                    case '-' -> l - r;
                    case '*' -> l * r;
                    case '/' -> r != 0 ? l / r : null;
                    default -> throw new IllegalStateException("Unknown operator: " + op);
                };
            }
            
            @Override
            public Set<String> getMetricNames() {
                Set<String> names = new HashSet<>();
                names.addAll(left.getMetricNames());
                names.addAll(right.getMetricNames());
                return names;
            }
        }
    }
}
