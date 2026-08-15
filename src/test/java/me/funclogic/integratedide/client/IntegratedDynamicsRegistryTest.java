package me.funclogic.integratedide.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.cyclops.integrateddynamics.api.evaluate.operator.IOperator;
import org.cyclops.integrateddynamics.core.evaluate.operator.Operators;
import org.junit.jupiter.api.Test;

/** Regression tests against the actual Integrated Dynamics operator registry, never a hand-written name table. */
class IntegratedDynamicsRegistryTest {
    private static final String USER_EXPRESSION = "equals(\"$minecraft:cobblestone\".withSize(10).size(), 10)";

    @Test
    void reproducesTheUnprefixedEqualsFailureAndReportsTheActualGlobalForm() {
        IOperator equals = Operators.RELATIONAL_EQUALS;
        Map<String, IOperator> globals = Operators.REGISTRY.getGlobalInteractOperators();

        assertEquals("anyEquals", equals.getGlobalInteractName());
        assertEquals("equals", equals.getScopedInteractName());
        assertSame(equals, globals.get(equals.getGlobalInteractName()));
        assertFalse(globals.containsKey("equals"));

        LogicProgrammerCatalog catalog = LogicProgrammerCatalog.create();
        var compilation = catalog.compile(USER_EXPRESSION);
        assertFalse(compilation.valid());
        assertEquals(0, compilation.errorPosition());
        assertTrue(compilation.message().contains("No registered global function named 'equals'"));
        assertTrue(compilation.message().contains("anyEquals(...)"));

        var corrected = catalog.compile("anyEquals(\"$minecraft:cobblestone\".withSize(10).size(), 10.toLong())");
        assertTrue(corrected.valid(), corrected.message());
    }
}
