package me.funclogic.integratedide.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import me.funclogic.integratedide.expr.ExpressionCompiler;
import org.junit.jupiter.api.Test;

class LogicProgrammerPlanTest {
    private static final ExpressionCompiler.TypeInfo ANY = type("any");
    private static final ExpressionCompiler.TypeInfo STRING = type("string");
    private static final ExpressionCompiler.TypeInfo ITEM = type("itemstack");
    private static final ExpressionCompiler.TypeInfo FLUID = type("fluidstack");
    private static final ExpressionCompiler.TypeInfo TAG = type("ingredients");
    private static final ExpressionCompiler.TypeInfo INTEGER = type("integer");
    private static final ExpressionCompiler.TypeInfo LONG = type("long");
    private static final ExpressionCompiler.TypeInfo BOOLEAN = type("boolean");

    @Test
    void executesACompiledPlanThroughTheLogicProgrammerContract() {
        var compilation = ExpressionCompiler.compile(
                "same(\"$minecraft:cobblestone\".withSize(10).size(), 10.toLong())", catalog());

        assertTrue(compilation.valid(), compilation.message());
        assertEquals(7, compilation.steps().size());

        SimulatedLogicProgrammer programmer = new SimulatedLogicProgrammer();
        execute(compilation, programmer);

        assertEquals(List.of("test:itemstack_withsize", "test:itemstack_size", "test:number_to_long",
                "test:any_equals"), programmer.operators);
        assertEquals(List.of("itemstack", "integer", "integer"), programmer.valueTypes);
        assertEquals(List.of("itemstack:minecraft:cobblestone", "integer:10", "integer:10"), programmer.literals);
        assertEquals(7, programmer.writtenCards.size());
        assertEquals(compilation.rootId(), programmer.writtenCards.getLast());
        assertEquals(List.of(
                "type:itemstack", "literal:itemstack:minecraft:cobblestone", "write:v0",
                "type:integer", "literal:integer:10", "write:v1",
                "operator:test:itemstack_withsize", "input:v0", "input:v1", "write:v2",
                "operator:test:itemstack_size", "input:v2", "write:v3",
                "type:integer", "literal:integer:10", "write:v4",
                "operator:test:number_to_long", "input:v4", "write:v5",
                "operator:test:any_equals", "input:v3", "input:v5", "write:v6"), programmer.events);
    }

    @Test
    void configuresAllStaticLiteralKindsBeforeWritingTheirDependentOperator() {
        var compilation = ExpressionCompiler.compile(
                "pack(\"plain\", true, \"$minecraft:cobblestone\", \"$minecraft:water\", \"@minecraft\", \"#minecraft:planks\")",
                catalog());

        assertTrue(compilation.valid(), compilation.message());
        SimulatedLogicProgrammer programmer = new SimulatedLogicProgrammer();
        execute(compilation, programmer);

        assertEquals(List.of("string:plain", "boolean:true", "itemstack:minecraft:cobblestone",
                "fluidstack:minecraft:water", "string:minecraft", "ingredients:minecraft:planks"), programmer.literals);
        assertEquals(List.of("test:pack"), programmer.operators);
        assertEquals(7, programmer.writtenCards.size());
        assertEquals(compilation.rootId(), programmer.writtenCards.getLast());
    }

    @Test
    void reusesVirtualCardsAndOnlyInsertsInputsAlreadyCreatedByThePlan() {
        var compilation = ExpressionCompiler.compile(
                "{stack} = \"$minecraft:cobblestone\".withSize(10)\nsame({stack}.size(), 10.toLong())",
                catalog());

        assertTrue(compilation.valid(), compilation.message());
        SimulatedLogicProgrammer programmer = new SimulatedLogicProgrammer();
        execute(compilation, programmer);

        assertEquals(7, programmer.writtenCards.size());
        assertEquals(List.of("v0", "v1", "v2", "v4", "v3", "v5"), programmer.insertedInputs);
    }

    @Test
    void acceptsAnAvailableExternalCardWithoutSelectingConfiguringOrWritingIt() {
        var compilation = ExpressionCompiler.compile("usesExternal({42})", catalog());

        assertTrue(compilation.valid(), compilation.message());
        SimulatedLogicProgrammer programmer = new SimulatedLogicProgrammer();
        execute(compilation, programmer);

        assertEquals(List.of("42"), programmer.externalReferences);
        assertEquals(List.of("test:uses_external"), programmer.operators);
        assertEquals(List.of("v0"), programmer.insertedInputs);
        assertEquals(List.of("v1"), programmer.writtenCards);
    }

    @Test
    void rejectsArityAndTypeErrorsBeforeAnyLogicProgrammerActionCanBePlanned() {
        var arity = ExpressionCompiler.compile("same(10)", catalog());
        var type = ExpressionCompiler.compile("\"$minecraft:cobblestone\".withSize(true)", catalog());

        assertFalse(arity.valid());
        assertFalse(type.valid());
        assertTrue(arity.message().contains("expects 2 to 2 argument(s), but received 1"));
        assertTrue(type.message().contains("Expected integer, but this expression produces boolean"));
    }

    private static void execute(ExpressionCompiler.Compilation compilation, SimulatedLogicProgrammer programmer) {
        Set<String> availableCards = new HashSet<>();
        for (ExpressionCompiler.CardStep step : compilation.steps()) {
            LogicProgrammerPlanDispatcher.select(step, programmer);
            LogicProgrammerPlanDispatcher.configure(step, programmer);
            if (step.kind() == ExpressionCompiler.StepKind.EXTERNAL_REFERENCE) {
                programmer.externalReferences.add(step.value());
                availableCards.add(step.id());
                continue;
            }
            for (String input : step.inputs()) {
                assertTrue(availableCards.contains(input), "Missing input card " + input + " for " + step.id());
                programmer.insertedInputs.add(input);
                programmer.events.add("input:" + input);
            }
            if (step.createsVariableCard()) {
                programmer.writtenCards.add(step.id());
                availableCards.add(step.id());
                programmer.events.add("write:" + step.id());
            }
        }
    }

    private static ExpressionCompiler.Catalog catalog() {
        return new ExpressionCompiler.Catalog() {
            @Override
            public ExpressionCompiler.FunctionInfo globalFunction(String name) {
                return switch (name) {
                    case "same" -> function("test:any_equals", List.of(ANY, ANY), BOOLEAN);
                    case "pack" -> function("test:pack", List.of(STRING, BOOLEAN, ITEM, FLUID, STRING, TAG), BOOLEAN);
                    case "usesExternal" -> function("test:uses_external", List.of(ITEM), BOOLEAN);
                    default -> null;
                };
            }

            @Override
            public ExpressionCompiler.FunctionInfo memberFunction(ExpressionCompiler.TypeInfo receiver, String name) {
                if (receiver.equals(ITEM) && name.equals("withSize")) {
                    return function("test:itemstack_withsize", List.of(ITEM, INTEGER), ITEM);
                }
                if (receiver.equals(ITEM) && name.equals("size")) {
                    return function("test:itemstack_size", List.of(ITEM), LONG);
                }
                if (receiver.equals(INTEGER) && name.equals("toLong")) {
                    return function("test:number_to_long", List.of(INTEGER), LONG);
                }
                return null;
            }

            @Override
            public ExpressionCompiler.TypeInfo literalType(ExpressionCompiler.LiteralKind kind, String value,
                                                            ExpressionCompiler.TypeInfo expectedType) {
                return switch (kind) {
                    case ITEM -> value.equals("minecraft:water") ? FLUID : ITEM;
                    case INTEGER -> expectedType != null && expectedType.equals(LONG) ? LONG : INTEGER;
                    case DECIMAL -> LONG;
                    case STRING, MOD -> STRING;
                    case BOOLEAN -> BOOLEAN;
                    case TAG -> TAG;
                };
            }

            @Override
            public boolean isAssignable(ExpressionCompiler.TypeInfo actual, ExpressionCompiler.TypeInfo expected) {
                return actual.equals(expected) || expected.equals(ANY);
            }
        };
    }

    private static ExpressionCompiler.FunctionInfo function(String id, List<ExpressionCompiler.TypeInfo> inputs,
                                                             ExpressionCompiler.TypeInfo output) {
        return new ExpressionCompiler.FunctionInfo(id, id, inputs, output, inputs.size());
    }

    private static ExpressionCompiler.TypeInfo type(String name) {
        return new ExpressionCompiler.TypeInfo("test:" + name, name);
    }

    private static final class SimulatedLogicProgrammer implements LogicProgrammerPlanSink {
        private final List<String> operators = new ArrayList<>();
        private final List<String> valueTypes = new ArrayList<>();
        private final List<String> literals = new ArrayList<>();
        private final List<String> insertedInputs = new ArrayList<>();
        private final List<String> writtenCards = new ArrayList<>();
        private final List<String> externalReferences = new ArrayList<>();
        private final List<String> events = new ArrayList<>();

        @Override
        public void selectOperator(String operatorId) {
            operators.add(operatorId);
            events.add("operator:" + operatorId);
        }

        @Override
        public void selectValueType(String valueTypeId) {
            String valueType = valueTypeId.substring("test:".length());
            valueTypes.add(valueType);
            events.add("type:" + valueType);
        }

        @Override
        public void configureLiteral(ExpressionCompiler.CardStep step) {
            String literal = step.outputTypeId().substring("test:".length()) + ":" + step.value();
            literals.add(literal);
            events.add("literal:" + literal);
        }
    }
}
