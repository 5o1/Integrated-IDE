package me.funclogic.integratedide.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import me.funclogic.integratedide.expr.ExpressionCompiler;
import org.cyclops.integrateddynamics.api.evaluate.operator.IOperator;
import org.cyclops.integrateddynamics.api.evaluate.variable.IValueType;
import org.cyclops.integrateddynamics.core.evaluate.operator.Operators;
import org.cyclops.integrateddynamics.core.evaluate.variable.ValueTypes;
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

        var corrected = catalog.compile("anyEquals(\"$minecraft:cobblestone\".withSize(10).size(), 10)");
        assertTrue(corrected.valid(), corrected.message());
    }

    @Test
    void exposesCategoryScopedMemberFunctionsForConcreteValueTypes() {
        LogicProgrammerCatalog catalog = LogicProgrammerCatalog.create();
        String assignment = "{item} = \"$minecraft:cobblestone\"\n";
        ExpressionCompiler.Compilation itemCompilation = catalog.compile(assignment);
        assertTrue(itemCompilation.valid(), itemCompilation.message());
        ExpressionCompiler.TypeInfo itemType = itemCompilation.virtualTypes().get("item");
        IValueType<?> itemValueType = null;
        for (IValueType<?> type : ValueTypes.REGISTRY.getValueTypes()) {
            if (type.getUniqueName().toString().equals(itemType.id())) {
                itemValueType = type;
                break;
            }
        }
        assertNotNull(itemValueType, "The catalog item literal type must be present in Dynamic's type registry.");
        for (Map.Entry<IValueType<?>, Map<String, IOperator>> entry
                : Operators.REGISTRY.getScopedInteractOperators().entrySet()) {
            IValueType<?> category = entry.getKey();
            if (category == itemValueType || !category.correspondsTo(itemValueType) || entry.getValue().isEmpty()) {
                continue;
            }
            String memberName = entry.getValue().keySet().iterator().next();
            assertNotNull(catalog.memberFunction(itemType, memberName),
                    "A category member must be callable through its concrete value type.");
            String completionSource = assignment + "{item}." + memberName;
            assertTrue(catalog.completions(completionSource, completionSource.length(), null, false).stream()
                            .anyMatch(completion -> completion.insertion().endsWith(memberName + "(")),
                    "The callable category member must also appear in completion results.");
            return;
        }
        throw new AssertionError("The installed Integrated Dynamics registry did not expose a category-scoped item operator.");
    }

    @Test
    void derivesItemAndFluidLiteralCardsFromRegistryTypesRatherThanDisplayNames() {
        LogicProgrammerCatalog catalog = LogicProgrammerCatalog.create();

        var item = catalog.compile("\"$minecraft:cobblestone\"");
        var fluid = catalog.compile("\"$minecraft:water\"");
        var missing = catalog.compile("\"$integratedide:not_a_registered_resource\"");

        assertTrue(item.valid(), item.message());
        assertEquals(ExpressionCompiler.StepKind.STATIC_ITEM, item.steps().getFirst().kind());
        assertEquals(ValueTypes.OBJECT_ITEMSTACK.getUniqueName().toString(), item.steps().getFirst().outputTypeId());

        assertTrue(fluid.valid(), fluid.message());
        assertEquals(ExpressionCompiler.StepKind.STATIC_FLUID, fluid.steps().getFirst().kind());
        assertEquals(ValueTypes.OBJECT_FLUIDSTACK.getUniqueName().toString(), fluid.steps().getFirst().outputTypeId());

        assertFalse(missing.valid(), "An unknown resource must not pretend to be an item or fluid at compile time.");
    }

    @Test
    void rejectsHeterogeneousInputsUsingDynamicsRegisteredOperatorValidation() {
        LogicProgrammerCatalog catalog = LogicProgrammerCatalog.create();

        var invalid = RuntimeExpressionValidator.validate(
                catalog.compile("anyEquals(\"$minecraft:cobblestone\", 10)"));

        assertFalse(invalid.valid(), "Dynamic must reject equality inputs with incompatible concrete types.");
    }

    @Test
    void neverReturnsMoreCandidatesThanThePopupCanDisplay() {
        LogicProgrammerCatalog catalog = LogicProgrammerCatalog.create();
        String source = "any";

        assertTrue(catalog.completions(source, source.length(), null, false).size()
                <= LogicProgrammerCatalog.MAX_COMPLETIONS);
    }
}
