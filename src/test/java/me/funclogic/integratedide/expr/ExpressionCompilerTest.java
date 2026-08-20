package me.funclogic.integratedide.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExpressionCompilerTest {
    private static final ExpressionCompiler.TypeInfo STRING = new ExpressionCompiler.TypeInfo("id:string", "string");
    private static final ExpressionCompiler.TypeInfo BOOLEAN = new ExpressionCompiler.TypeInfo("id:boolean", "boolean");
    private static final ExpressionCompiler.TypeInfo ITEM = new ExpressionCompiler.TypeInfo("id:itemstack", "itemstack");
    private static final ExpressionCompiler.TypeInfo FLUID = new ExpressionCompiler.TypeInfo("id:fluidstack", "fluidstack");
    private static final ExpressionCompiler.TypeInfo INGREDIENTS = new ExpressionCompiler.TypeInfo("id:ingredients", "ingredients");

    @Test
    void compilesRegisteredGlobalFunctionWithoutHardcodedNames() {
        var compilation = ExpressionCompiler.compile("join(\"a\", \"b\")", catalog());

        assertTrue(compilation.valid(), compilation.message());
        assertEquals(3, compilation.steps().size());
        assertEquals("test:join", compilation.steps().getLast().value());
    }

    @Test
    void compilesVirtualVariableAndRegisteredMemberFunction() {
        var compilation = ExpressionCompiler.compile("{x} = join(\"a\", \"b\")\n{x}.empty()", catalog());

        assertTrue(compilation.valid(), compilation.message());
        assertEquals(4, compilation.steps().size());
        assertEquals("test:empty", compilation.steps().getLast().value());
        assertEquals(STRING, compilation.virtualTypes().get("x"));
    }

    @Test
    void choosesFluidCardsForFluidIdentifiersAndIngredientCardsForTags() {
        var compilation = ExpressionCompiler.compile("fluid(\"$minecraft:water\")\ningredient(\"#minecraft:planks\")", catalog());

        assertTrue(compilation.valid(), compilation.message());
        assertTrue(compilation.steps().stream().anyMatch(step -> step.kind() == ExpressionCompiler.StepKind.STATIC_FLUID));
        assertTrue(compilation.steps().stream().anyMatch(step -> step.kind() == ExpressionCompiler.StepKind.STATIC_TAG));
    }

    @Test
    void requiresQuotedResourceLiteralsAndAllowsEscapedResourcePrefixesInStrings() {
        var bare = ExpressionCompiler.compile("fluid($minecraft:water)", catalog());
        var escaped = ExpressionCompiler.compile("join(\"\\@literal\", \"text\")", catalog());

        assertFalse(bare.valid());
        assertTrue(bare.message().contains("Resource literals must be wrapped in double quotes."));
        assertTrue(escaped.valid(), escaped.message());
        assertEquals("@literal", escaped.steps().getFirst().value());
        assertEquals(ExpressionCompiler.StepKind.STATIC_TEXT, escaped.steps().getFirst().kind());
    }

    @Test
    void treatsDotAfterIntegerAsMemberAccess() {
        var compilation = ExpressionCompiler.compile("1.empty()", catalog());

        assertTrue(compilation.valid(), compilation.message());
    }

    @Test
    void rejectsDuplicateOrForwardTemporaryVariables() {
        var duplicate = ExpressionCompiler.compile("{x} = join(\"a\", \"b\")\n{x} = join(\"c\", \"d\")", catalog());
        var forward = ExpressionCompiler.compile("{x}.empty()", catalog());

        assertFalse(duplicate.valid());
        assertFalse(forward.valid());
        assertTrue(duplicate.errorPosition() >= 0);
        assertTrue(forward.errorPosition() >= 0);
    }

    @Test
    void modelsNumericBracesAsAnExternalVariableCardReference() {
        var compilation = ExpressionCompiler.compile("join({42}, \"text\")", catalog());

        assertTrue(compilation.valid(), compilation.message());
        assertEquals(3, compilation.steps().size());
        assertEquals(ExpressionCompiler.StepKind.EXTERNAL_REFERENCE, compilation.steps().getFirst().kind());
        assertEquals("42", compilation.steps().getFirst().value());
        assertFalse(compilation.steps().getFirst().createsVariableCard());
        assertEquals(2, compilation.steps().stream().filter(ExpressionCompiler.CardStep::createsVariableCard).count());
    }

    @Test
    void acceptsAnExternalVariableCardAsAStandaloneExpression() {
        var compilation = ExpressionCompiler.compile("{42}", catalog());

        assertTrue(compilation.valid(), compilation.message());
        assertEquals(1, compilation.steps().size());
        assertFalse(compilation.steps().getFirst().createsVariableCard());
    }

    @Test
    void acceptsLineCommentsWithoutChangingStatementRoots() {
        var compilation = ExpressionCompiler.compile("join(\"a\", \"b\") // build greeting\n// ignored line\n{42} // card", catalog());

        assertTrue(compilation.valid(), compilation.message());
        assertEquals(2, compilation.statementRoots().size());
        assertEquals(0, compilation.statementRoots().getFirst().sourceStart());
        assertEquals("42", compilation.steps().getLast().value());
    }

    @Test
    void keepsEscapedStringsWhenLexingAndLowering() {
        var compilation = ExpressionCompiler.compile("join(\"first\\nline\", \"second\")", catalog());

        assertTrue(compilation.valid(), compilation.message());
        assertEquals("first\nline", compilation.steps().getFirst().value());
    }

    @Test
    void rejectsStatementsWithoutNewlinesAndUnterminatedStrings() {
        var sameLine = ExpressionCompiler.compile("join(\"a\", \"b\") join(\"c\", \"d\")", catalog());
        var unterminated = ExpressionCompiler.compile("join(\"a, \"b\")", catalog());

        assertFalse(sameLine.valid());
        assertFalse(unterminated.valid());
        assertTrue(sameLine.message().contains("Statements must be separated by a newline."));
        assertTrue(unterminated.message().contains("Unterminated string literal."));
    }

    private static ExpressionCompiler.Catalog catalog() {
        return new ExpressionCompiler.Catalog() {
            @Override
            public ExpressionCompiler.FunctionInfo globalFunction(String name) {
                return switch (name) {
                    case "join" -> function("test:join", "join", List.of(STRING, STRING), STRING, 2);
                    case "fluid" -> function("test:fluid", "fluid", List.of(FLUID), BOOLEAN, 1);
                    case "ingredient" -> function("test:ingredient", "ingredient", List.of(INGREDIENTS), BOOLEAN, 1);
                    default -> null;
                };
            }

            @Override
            public ExpressionCompiler.FunctionInfo memberFunction(ExpressionCompiler.TypeInfo receiver, String name) {
                if (receiver.equals(STRING) && name.equals("empty")) {
                    return function("test:empty", "empty", List.of(STRING), BOOLEAN, 1);
                }
                return null;
            }

            @Override
            public ExpressionCompiler.TypeInfo literalType(ExpressionCompiler.LiteralKind kind, String value,
                                                            ExpressionCompiler.TypeInfo expected) {
                return switch (kind) {
                    case STRING, MOD -> STRING;
                    case ITEM -> value.equals("minecraft:water") ? FLUID : ITEM;
                    case TAG -> INGREDIENTS;
                    case BOOLEAN -> BOOLEAN;
                    case INTEGER, DECIMAL -> STRING;
                };
            }

            @Override
            public ExpressionCompiler.StepKind literalStepKind(ExpressionCompiler.LiteralKind kind,
                                                                ExpressionCompiler.TypeInfo type) {
                return kind == ExpressionCompiler.LiteralKind.ITEM && type.equals(FLUID)
                        ? ExpressionCompiler.StepKind.STATIC_FLUID
                        : ExpressionCompiler.Catalog.super.literalStepKind(kind, type);
            }

            @Override
            public boolean isAssignable(ExpressionCompiler.TypeInfo actual, ExpressionCompiler.TypeInfo expected) {
                return actual.equals(expected);
            }
        };
    }

    private static ExpressionCompiler.FunctionInfo function(String id, String name, List<ExpressionCompiler.TypeInfo> inputs,
                                                             ExpressionCompiler.TypeInfo output, int required) {
        return new ExpressionCompiler.FunctionInfo(id, name, inputs, output, required);
    }
}
