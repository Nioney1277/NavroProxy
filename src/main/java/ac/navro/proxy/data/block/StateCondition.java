package ac.navro.proxy.data.block;

import org.cloudburstmc.nbt.NbtMap;

final class StateCondition {
    private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    private final String expression;
    private final NbtMap states;
    private final boolean modernPrecedence;
    private final boolean fixedNegativeDivision;
    private int offset;
    private int depth;

    private StateCondition(String expression, NbtMap states, boolean modernPrecedence, boolean fixedNegativeDivision) {
        this.expression = expression;
        this.states = states;
        this.modernPrecedence = modernPrecedence;
        this.fixedNegativeDivision = fixedNegativeDivision;
    }

    static Boolean evaluate(String expression, NbtMap states) {
        return evaluate(expression, states, true, true);
    }

    static Boolean evaluate(String expression, NbtMap states, Integer version, int protocol) {
        if (version == null) {
            Boolean modern = evaluate(expression, states, true, true);
            Boolean legacy = evaluate(expression, states, false, false);
            return java.util.Objects.equals(modern, legacy) ? modern : null;
        }

        int shift = protocol >= 2207 ? 0 : 1;
        return evaluate(expression, states, version >= 6 + shift, version >= 7 + shift);
    }

    private static Boolean evaluate(String expression, NbtMap states, boolean modernPrecedence, boolean fixedNegativeDivision) {
        if (expression.length() > 4096) return null;
        try {
            var parser = new StateCondition(expression, states, modernPrecedence, fixedNegativeDivision);
            boolean result = truth(parser.or());
            parser.whitespace();
            return parser.offset == expression.length() ? result : null;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private Object or() {
        if (!modernPrecedence) return legacyLogical();
        Object value = and();
        while (take("||")) {
            Object next = and();
            value = truth(value) || truth(next) ? 1F : 0F;
        }
        return value;
    }

    private Object legacyLogical() {
        Object value = comparison();
        while (true) {
            if (take("||")) {
                Object next = comparison();
                value = truth(value) || truth(next) ? 1F : 0F;
            } else if (take("&&")) {
                Object next = comparison();
                value = truth(value) && truth(next) ? 1F : 0F;
            } else return value;
        }
    }

    private Object and() {
        Object value = comparison();
        while (take("&&")) {
            Object next = comparison();
            value = truth(value) && truth(next) ? 1F : 0F;
        }
        return value;
    }

    private Object comparison() {
        Object left = sum();
        String operator = null;
        for (String candidate : new String[]{"==", "!=", "<=", ">=", "<", ">"}) {
            if (take(candidate)) { operator = candidate; break; }
        }
        if (operator == null) return left;
        Object right = sum();
        boolean result;
        if (left instanceof String first && right instanceof String second) {
            if (!operator.equals("==") && !operator.equals("!=")) throw new IllegalArgumentException();
            if (!first.equals(second) && first.equalsIgnoreCase(second)) throw new IllegalArgumentException();
            result = first.equals(second);
            if (operator.equals("!=")) result = !result;
        } else if (left instanceof Number first && right instanceof Number second) {
            float firstValue = first.floatValue(), secondValue = second.floatValue();
            result = switch (operator) {
                case "==" -> firstValue == secondValue;
                case "!=" -> firstValue != secondValue;
                case "<=" -> firstValue <= secondValue;
                case ">=" -> firstValue >= secondValue;
                case "<" -> firstValue < secondValue;
                case ">" -> firstValue > secondValue;
                default -> throw new IllegalArgumentException();
            };
        } else throw new IllegalArgumentException();
        return result ? 1F : 0F;
    }

    private Object sum() {
        Object value = product();
        while (true) {
            if (take("+")) value = finite(number(value) + number(product()));
            else if (take("-")) value = finite(number(value) - number(product()));
            else return value;
        }
    }

    private Object product() {
        Object value = primary();
        while (true) {
            if (take("*")) value = finite(number(value) * number(primary()));
            else if (take("/")) {
                float numerator = number(value);
                float denominator = number(primary());
                if (denominator == 0F || denominator < 0F && !fixedNegativeDivision) throw new IllegalArgumentException();
                value = finite(numerator / denominator);
            } else return value;
        }
    }

    private static float number(Object value) {
        if (value instanceof Number number) return number.floatValue();
        throw new IllegalArgumentException();
    }

    private static float finite(float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException();
        return value;
    }

    private Object primary() {
        if (++depth > 64) throw new IllegalArgumentException();
        try { return value(); }
        finally { depth--; }
    }

    private Object value() {
        if (take("!")) return truth(primary()) ? 0F : 1F;
        if (take("-")) return -number(primary());
        if (take("+")) return number(primary());
        if (take("(")) { Object result = or(); require(")"); return result; }
        if (take("q.block_state") || take("query.block_state") || take("q.block_property") || take("query.block_property")) {
            require("(");
            String name = string();
            require(")");
            Object value = states.get(name);
            if (value instanceof String || value instanceof Byte || value instanceof Integer) return value;
            throw new IllegalArgumentException();
        }
        if (take("true")) return 1F;
        if (take("false")) return 0F;
        whitespace();
        if (offset < expression.length() && expression.charAt(offset) == '\'') return string();
        var matcher = NUMBER.matcher(expression).region(offset, expression.length());
        if (!matcher.lookingAt()) throw new IllegalArgumentException();
        float number = Float.parseFloat(matcher.group());
        offset = matcher.end();
        if (!Float.isFinite(number)) throw new IllegalArgumentException();
        return number;
    }

    private String string() {
        whitespace();
        if (offset == expression.length()) throw new IllegalArgumentException();
        char quote = expression.charAt(offset++);
        if (quote != '\'') throw new IllegalArgumentException();
        int start = offset;
        while (offset < expression.length() && expression.charAt(offset) != quote) {
            if (expression.charAt(offset) == '\\') throw new IllegalArgumentException();
            offset++;
        }
        if (offset == expression.length()) throw new IllegalArgumentException();
        String result = expression.substring(start, offset++);
        return result;
    }

    private static boolean truth(Object value) {
        if (value instanceof Number number) return number.floatValue() != 0F;
        throw new IllegalArgumentException();
    }

    private void require(String token) { if (!take(token)) throw new IllegalArgumentException(); }
    private boolean take(String token) {
        whitespace();
        if (!expression.regionMatches(true, offset, token, 0, token.length())) return false;
        offset += token.length();
        return true;
    }
    private void whitespace() { while (offset < expression.length() && Character.isWhitespace(expression.charAt(offset))) offset++; }
}
