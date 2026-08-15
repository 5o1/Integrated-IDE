package me.funclogic.integratedide.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PartialCallAnalysisTest {
    @Test
    void findsTheEmptyArgumentOfTheInnermostCall() {
        var call = PartialCallAnalysis.at("outer(inner(),  ", "outer(inner(),  ".length()).orElseThrow();

        assertEquals("outer", call.name());
        assertNull(call.receiver());
        assertEquals(1, call.argumentIndex());
        assertTrue(call.emptyArgument());
    }

    @Test
    void ignoresParenthesesAndCommasInsideStrings() {
        var call = PartialCallAnalysis.at("join(\"a,b)\", ", "join(\"a,b)\", ".length()).orElseThrow();

        assertEquals("join", call.name());
        assertEquals(1, call.argumentIndex());
        assertTrue(call.emptyArgument());
    }

    @Test
    void identifiesMemberCallsAndNonEmptyArguments() {
        var call = PartialCallAnalysis.at("{value}.empty(text", "{value}.empty(text".length()).orElseThrow();

        assertEquals("empty", call.name());
        assertEquals("{value}", call.receiver());
        assertFalse(call.emptyArgument());
    }
}
