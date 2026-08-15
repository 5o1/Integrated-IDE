package me.funclogic.integratedide.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import me.funclogic.integratedide.expr.ExpressionCompiler;
import org.cyclops.integrateddynamics.api.evaluate.operator.IOperator;
import org.cyclops.integrateddynamics.core.evaluate.operator.Operators;
import org.junit.jupiter.api.Test;

/** Regression tests against the actual Integrated Dynamics operator registry, never a hand-written name table. */
class IntegratedDynamicsRegistryTest {
    private static final String USER_EXPRESSION = "equals(\"$minecraft:cobblestone\".withSize(10).size(), 10)";

    @Test
    void reproducesTheUnprefixedEqualsFailureFromTheActualDynamicsRegistry() {
        IOperator equals = Operators.RELATIONAL_EQUALS;
        Map<String, IOperator> globals = Operators.REGISTRY.getGlobalInteractOperators();

        assertEquals("anyEquals", equals.getGlobalInteractName());
        assertEquals("equals", equals.getScopedInteractName());
        assertSame(equals, globals.get(equals.getGlobalInteractName()));
        assertFalse(globals.containsKey("equals"));

        ExpressionCompiler.Compilation compilation = ExpressionCompiler.compile(USER_EXPRESSION,
                new RegistryBackedCatalog(globals));
        assertFalse(compilation.valid());
        assertEquals(0, compilation.errorPosition());
        assertTrue(compilation.message().contains("No registered global function named 'equals'"));
    }

    /** Uses the live registry map for names; unsupported calls must not be silently invented in tests. */
    private static final class RegistryBackedCatalog implements ExpressionCompiler.Catalog {
        private final Map<String, IOperator> globals;

        private RegistryBackedCatalog(Map<String, IOperator> globals) {
            this.globals = globals;
        }

        @Override
        public ExpressionCompiler.FunctionInfo globalFunction(String name) {
            if (globals.containsKey(name)) {
                throw new AssertionError("The regression expression must be rejected before its arguments are parsed.");
            }
            return null;
        }

        @Override
        public ExpressionCompiler.FunctionInfo memberFunction(ExpressionCompiler.TypeInfo receiver, String name) {
            return null;
        }

        @Override
        public ExpressionCompiler.TypeInfo literalType(ExpressionCompiler.LiteralKind kind, String value,
                                                        ExpressionCompiler.TypeInfo expectedType) {
            return expectedType;
        }

        @Override
        public boolean isAssignable(ExpressionCompiler.TypeInfo actual, ExpressionCompiler.TypeInfo expected) {
            return actual.equals(expected);
        }
    }
}
