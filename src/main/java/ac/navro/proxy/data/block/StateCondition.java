package ac.navro.proxy.data.block;

import org.cloudburstmc.nbt.NbtMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;

final class StateCondition {
    private static final Pattern NUMBER = Pattern.compile("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    private final String expression;
    private final NbtMap states;
    private final boolean rightAssociativeConditional;
    private final boolean modernPrecedence;
    private final boolean fixedNegativeDivision;
    private int offset;
    private int depth;
    private int conditionalDepth;
    private boolean evaluating = true;

    private StateCondition(String expression, NbtMap states, boolean rightAssociativeConditional,
                           boolean modernPrecedence, boolean fixedNegativeDivision) {
        this.expression = expression;
        this.states = states;
        this.rightAssociativeConditional = rightAssociativeConditional;
        this.modernPrecedence = modernPrecedence;
        this.fixedNegativeDivision = fixedNegativeDivision;
    }

    static Boolean evaluate(String expression, NbtMap states) {
        return evaluate(expression, states, true, true, true);
    }

    static Boolean evaluate(String expression, NbtMap states, Integer version, int protocol) {
        if (version == null) {
            Boolean result = evaluate(expression, states, false, false, false);
            if (result == null) return null;
            for (int boundary = 5; boundary <= 7; boundary++) {
                Boolean candidate = evaluate(expression, states, boundary, 2225);
                if (!result.equals(candidate)) return null;
            }
            return result;
        }
        int shift = protocol >= 2207 ? 0 : 1;
        return evaluate(expression, states, version >= 5 + shift, version >= 6 + shift, version >= 7 + shift);
    }

    private static Boolean evaluate(String expression, NbtMap states, boolean rightAssociativeConditional,
                                    boolean modernPrecedence, boolean fixedNegativeDivision) {
        if (expression.length() > 4096) return null;
        try {
            var parser = new StateCondition(expression, states, rightAssociativeConditional,
                    modernPrecedence, fixedNegativeDivision);
            Object result = parser.conditional();
            parser.whitespace();
            return parser.offset == expression.length() ? truth(result) : null;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private Object conditional() {
        if (++conditionalDepth > 64) throw new IllegalArgumentException();
        try {
            Object value = or();
            while (take("?")) {
                boolean selectTrue = evaluating && truth(value);
                Object whenTrue = parseWhen(selectTrue, rightAssociativeConditional ? this::conditional : this::or);
                require(":");
                Object whenFalse = parseWhen(!selectTrue, rightAssociativeConditional ? this::conditional : this::or);
                value = evaluating ? selectTrue ? whenTrue : whenFalse : 0F;
                if (rightAssociativeConditional) break;
            }
            return value;
        } finally {
            conditionalDepth--;
        }
    }

    private Object parseWhen(boolean selected, Supplier<Object> parser) {
        boolean previous = evaluating;
        evaluating = previous && selected;
        try {
            return parser.get();
        } finally {
            evaluating = previous;
        }
    }

    private Object or() {
        if (!modernPrecedence) return legacyLogical();
        Object value = and();
        while (take("||")) {
            boolean first = evaluating && truth(value);
            Object next = parseWhen(!first, this::and);
            value = evaluating && (first || truth(next)) ? 1F : 0F;
        }
        return value;
    }

    private Object and() {
        Object value = comparison();
        while (take("&&")) {
            boolean first = evaluating && truth(value);
            Object next = parseWhen(first, this::comparison);
            value = evaluating && first && truth(next) ? 1F : 0F;
        }
        return value;
    }

    private Object legacyLogical() {
        Object value = comparison();
        while (true) {
            if (take("||")) {
                boolean first = evaluating && truth(value);
                Object next = parseWhen(!first, this::comparison);
                value = evaluating && (first || truth(next)) ? 1F : 0F;
            } else if (take("&&")) {
                boolean first = evaluating && truth(value);
                Object next = parseWhen(first, this::comparison);
                value = evaluating && first && truth(next) ? 1F : 0F;
            } else return value;
        }
    }

    private Object comparison() {
        Object value = modernPrecedence ? relation() : sum();
        String operator;
        while ((operator = comparisonOperator()) != null) {
            Object right = modernPrecedence ? relation() : sum();
            value = evaluating ? compare(value, right, operator) : 0F;
        }
        return value;
    }

    private String comparisonOperator() {
        String[] operators = modernPrecedence ? new String[]{"==", "!="} : new String[]{"==", "!=", "<=", ">=", "<", ">"};
        for (String operator : operators) if (take(operator)) return operator;
        return null;
    }

    private Object relation() {
        Object value = sum();
        String operator;
        while ((operator = relationOperator()) != null) {
            Object right = sum();
            value = evaluating ? compare(value, right, operator) : 0F;
        }
        return value;
    }

    private String relationOperator() {
        for (String operator : new String[]{"<=", ">=", "<", ">"}) if (take(operator)) return operator;
        return null;
    }

    private static float compare(Object left, Object right, String operator) {
        boolean result;
        if (left instanceof String first && right instanceof String second) {
            if (!operator.equals("==") && !operator.equals("!=")) throw new IllegalArgumentException();
            if (!first.equals(second) && first.equalsIgnoreCase(second)) throw new IllegalArgumentException();
            result = first.equals(second);
            if (operator.equals("!=")) result = !result;
        } else {
            float first = number(left), second = number(right);
            result = switch (operator) {
                case "==" -> first == second;
                case "!=" -> first != second;
                case "<=" -> first <= second;
                case ">=" -> first >= second;
                case "<" -> first < second;
                case ">" -> first > second;
                default -> throw new IllegalArgumentException();
            };
        }
        return result ? 1F : 0F;
    }

    private Object sum() {
        Object value = product();
        while (true) {
            if (take("+")) {
                Object next = product();
                value = evaluating ? finite(number(value) + number(next)) : 0F;
            } else if (take("-")) {
                Object next = product();
                value = evaluating ? finite(number(value) - number(next)) : 0F;
            } else return value;
        }
    }

    private Object product() {
        Object value = primary();
        while (true) {
            if (take("*")) {
                Object next = primary();
                value = evaluating ? finite(number(value) * number(next)) : 0F;
            } else if (take("/")) {
                Object next = primary();
                if (!evaluating) {
                    value = 0F;
                    continue;
                }
                float denominator = number(next);
                if (denominator == 0F || denominator < 0F && !fixedNegativeDivision) throw new IllegalArgumentException();
                value = finite(number(value) / denominator);
            } else return value;
        }
    }

    private Object primary() {
        if (++depth > 64) throw new IllegalArgumentException();
        try {
            if (take("!")) {
                Object value = primary();
                return evaluating && !truth(value) ? 1F : 0F;
            }
            if (take("-")) {
                Object value = primary();
                return evaluating ? -number(value) : 0F;
            }
            if (take("+")) {
                Object value = primary();
                return evaluating ? number(value) : 0F;
            }
            if (take("(")) {
                Object value = conditional();
                require(")");
                return value;
            }
            whitespace();
            if (offset < expression.length() && expression.charAt(offset) == '\'') return string();
            var matcher = NUMBER.matcher(expression).region(offset, expression.length());
            if (matcher.lookingAt()) {
                float value = finite(Float.parseFloat(matcher.group()));
                offset = matcher.end();
                return value;
            }
            String name = identifier();
            if (name.equals("true")) return 1F;
            if (name.equals("false")) return 0F;
            if (name.equals("q.block_state") || name.equals("query.block_state")
                    || name.equals("q.block_property") || name.equals("query.block_property")) {
                require("(");
                String property = string();
                require(")");
                if (!evaluating) return 0F;
                Object value = states.get(property);
                if (value instanceof String || value instanceof Byte || value instanceof Integer) return value;
                throw new IllegalArgumentException();
            }
            if (take("(")) return function(name);
            if (!evaluating) return 0F;
            throw new IllegalArgumentException();
        } finally {
            depth--;
        }
    }

    private Object function(String name) {
        var arguments = new ArrayList<Object>();
        if (!take(")")) {
            do {
                arguments.add(conditional());
            } while (take(","));
            require(")");
        }
        int arity = switch (name) {
            case "math.abs", "math.ceil", "math.floor" -> 1;
            case "math.min", "math.max" -> 2;
            case "math.clamp" -> 3;
            default -> -1;
        };
        if (arity >= 0 && arguments.size() != arity) throw new IllegalArgumentException();
        if (!evaluating) return 0F;
        if (arity < 0) throw new IllegalArgumentException();
        float first = number(arguments.get(0));
        return switch (name) {
            case "math.abs" -> Math.abs(first);
            case "math.ceil" -> (float) Math.ceil(first);
            case "math.floor" -> (float) Math.floor(first);
            case "math.min" -> Math.min(first, number(arguments.get(1)));
            case "math.max" -> Math.max(first, number(arguments.get(1)));
            case "math.clamp" -> clamp(first, arguments);
            default -> throw new IllegalArgumentException();
        };
    }

    private static float clamp(float value, List<Object> arguments) {
        float minimum = number(arguments.get(1)), maximum = number(arguments.get(2));
        if (minimum > maximum) throw new IllegalArgumentException();
        return Math.min(Math.max(value, minimum), maximum);
    }

    private String identifier() {
        whitespace();
        int start = offset;
        if (offset == expression.length()) throw new IllegalArgumentException();
        char first = expression.charAt(offset);
        if (!(first >= 'a' && first <= 'z' || first >= 'A' && first <= 'Z' || first == '_')) {
            throw new IllegalArgumentException();
        }
        while (offset < expression.length()) {
            char value = expression.charAt(offset);
            if (!(value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z'
                    || value >= '0' && value <= '9' || value == '_' || value == '.')) break;
            offset++;
        }
        String name = expression.substring(start, offset).toLowerCase(Locale.ROOT);
        if (name.endsWith(".") || name.contains("..")) throw new IllegalArgumentException();
        return name;
    }

    private String string() {
        whitespace();
        require("'");
        int start = offset;
        while (offset < expression.length() && expression.charAt(offset) != '\'') {
            if (expression.charAt(offset) == '\\') throw new IllegalArgumentException();
            offset++;
        }
        if (offset == expression.length()) throw new IllegalArgumentException();
        return expression.substring(start, offset++);
    }

    private static float number(Object value) {
        if (value instanceof Number number) return number.floatValue();
        throw new IllegalArgumentException();
    }

    private static float finite(float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException();
        return value;
    }

    private static boolean truth(Object value) { return number(value) != 0F; }
    private void require(String token) { if (!take(token)) throw new IllegalArgumentException(); }

    private boolean take(String token) {
        whitespace();
        if (!expression.startsWith(token, offset)) return false;
        offset += token.length();
        return true;
    }

    private void whitespace() {
        while (offset < expression.length() && Character.isWhitespace(expression.charAt(offset))) offset++;
    }
}
