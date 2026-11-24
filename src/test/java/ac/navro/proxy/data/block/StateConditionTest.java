package ac.navro.proxy.data.block;

import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StateConditionTest {
    private static final NbtMap STATES = NbtMap.builder().putInt("test:level", 2)
            .putByte("test:enabled", (byte) 1).putString("test:kind", "north").build();

    @Test void ternariesSelectOnlyTheirExecutedBranch() {
        for (String expression : List.of(
                "q.block_state('test:enabled') ? q.block_state('test:level') == 2 : q.block_state('absent')",
                "0 ? q.block_state('absent') : 1",
                "1 ? 1 : math.random(0, 1)",
                "1 ? 1 : (1 / 0)",
                "(q.block_state('test:level') > 1 ? q.block_state('test:level') + 1 : 0) * 2 == 6",
                "(1 ? 'north' : q.block_state('absent')) == q.block_state('test:kind')")) {
            assertEquals(Boolean.TRUE, StateCondition.evaluate(expression, STATES), expression);
        }
        assertNull(StateCondition.evaluate("1 ? q.block_state('absent') : 1", STATES));
        assertNull(StateCondition.evaluate("0 ? 1 : math.random(0, 1)", STATES));
    }

    @Test void logicalOperatorsDoNotEvaluateSkippedRuntimeValues() {
        for (String expression : List.of("1 || q.block_state('absent')", "1 || math.random(0, 1)", "1 || 1 / 0")) {
            assertEquals(Boolean.TRUE, StateCondition.evaluate(expression, STATES), expression);
        }
        for (String expression : List.of("0 && q.block_state('absent')", "0 && math.random(0, 1)", "0 && 1 / 0")) {
            assertEquals(Boolean.FALSE, StateCondition.evaluate(expression, STATES), expression);
        }
        assertNull(StateCondition.evaluate("0 || q.block_state('absent')", STATES));
        assertNull(StateCondition.evaluate("1 && math.random(0, 1)", STATES));
    }

    @Test void malformedSkippedExpressionsRemainUnknown() {
        for (String expression : List.of("1 || (1 +)", "1 ? 1 : (", "1 || math.abs()",
                "1 || math.clamp(0, 1)", "1 || math..random(0, 1)", "1 || .", "1 ? 1", "true trailing")) {
            assertNull(StateCondition.evaluate(expression, STATES), expression);
        }
    }

    @Test void conditionalAssociativityUsesItsOwnVersionBoundary() {
        String expression = "1 ? 0 : 0 ? 0 : 1";
        assertEquals(Boolean.TRUE, StateCondition.evaluate(expression, STATES, 5, 2193));
        assertEquals(Boolean.FALSE, StateCondition.evaluate(expression, STATES, 6, 2193));
        assertEquals(Boolean.TRUE, StateCondition.evaluate(expression, STATES, 4, 2225));
        assertEquals(Boolean.FALSE, StateCondition.evaluate(expression, STATES, 5, 2225));
        assertEquals(Boolean.FALSE, StateCondition.evaluate("1 ? 0 : (0 ? 0 : 1)", STATES, 5, 2193));
        assertNull(StateCondition.evaluate(expression, STATES, null, 2193));
    }

    @Test void missingVersionsRequireAgreementAcrossIntermediateRuleVersions() {
        String expression = "(1 ? 0 : 0 ? 0 : 1) == (1 || 0 && 0)";
        assertEquals(Boolean.FALSE, StateCondition.evaluate(expression, STATES, 5, 2193));
        assertEquals(Boolean.TRUE, StateCondition.evaluate(expression, STATES, 6, 2193));
        assertEquals(Boolean.FALSE, StateCondition.evaluate(expression, STATES, 7, 2193));
        assertNull(StateCondition.evaluate(expression, STATES, null, 2193));
        assertEquals(Boolean.TRUE, StateCondition.evaluate("1 ? 1 : q.block_state('absent')", STATES, null, 2193));
    }

    @Test void comparisonsAndEqualitiesFollowVersionedPrecedence() {
        assertEquals(Boolean.TRUE, StateCondition.evaluate("2 == 3 < 1", STATES, 6, 2193));
        assertEquals(Boolean.FALSE, StateCondition.evaluate("2 == 3 < 1", STATES, 7, 2193));
        assertEquals(Boolean.TRUE, StateCondition.evaluate("1 < 2 == 1", STATES));
        assertEquals(Boolean.TRUE, StateCondition.evaluate("1 < 2 < 3", STATES));
    }

    @Test void deterministicFunctionsUseOnlyKnownNumericInputs() {
        for (String expression : List.of(
                "math.abs(-q.block_state('test:level')) == 2",
                "math.min(3, q.block_state('test:level')) == 2",
                "math.max(1, q.block_state('test:level')) == 2",
                "math.clamp(q.block_state('test:level'), 0, 1) == 1",
                "math.clamp(-2, 0, 1) == 0",
                "math.ceil(-1.5) == -1",
                "math.floor(-1.5) == -2",
                "MATH.MAX(math.abs(-2), 1) == 2")) {
            assertEquals(Boolean.TRUE, StateCondition.evaluate(expression, STATES), expression);
        }
        for (String expression : List.of("math.abs(q.block_state('absent'))",
                "math.max('north', 1)", "math.clamp(1, 2, 0)", "math.random(0, 1)")) {
            assertNull(StateCondition.evaluate(expression, STATES), expression);
        }
    }

    @Test void recursionAndLengthLimitsCoverConditionalNesting() {
        assertNull(StateCondition.evaluate("1 ? ".repeat(100) + "1" + " : 0".repeat(100), STATES));
        assertNull(StateCondition.evaluate("1".repeat(4097), STATES));
        assertNull(StateCondition.evaluate("!".repeat(100) + "1", STATES));
        assertEquals(Boolean.TRUE, StateCondition.evaluate("(((1 ? 2 : 3))) == 2", STATES));
        assertEquals(Boolean.TRUE, StateCondition.evaluate("(".repeat(63) + "1" + ")".repeat(63), STATES));
    }
}
