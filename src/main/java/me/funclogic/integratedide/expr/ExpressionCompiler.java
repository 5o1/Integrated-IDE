package me.funclogic.integratedide.expr;

import java.util.List;
import java.util.Map;

/** Public, registry-independent facade for the Novel language compiler. */
public final class ExpressionCompiler {
    private ExpressionCompiler() {
    }

    public static Compilation compile(String source, Catalog catalog) {
        try {
            ExpressionSyntax.Program program = new ExpressionParser(source == null ? "" : source).parseProgram();
            return new ExpressionPlanBuilder(catalog).compile(program);
        } catch (ExpressionCompileError error) {
            return Compilation.failure(error.position(), error.getMessage());
        }
    }

    public interface Catalog {
        FunctionInfo globalFunction(String name);

        /** Optional explanation when a member-only name was used as a global call. */
        default String missingGlobalFunctionHint(String name) {
            return null;
        }

        FunctionInfo memberFunction(TypeInfo receiverType, String name);

        TypeInfo literalType(LiteralKind kind, String value, TypeInfo expectedType);

        /**
         * Selects the ordinary Logic Programmer element for a literal. This
         * keeps literal representation semantic instead of inferring it from
         * a human-readable value-type name.
         */
        default StepKind literalStepKind(LiteralKind kind, TypeInfo type) {
            return switch (kind) {
                case STRING, INTEGER, DECIMAL -> StepKind.STATIC_TEXT;
                case MOD -> StepKind.STATIC_MOD;
                case BOOLEAN -> StepKind.STATIC_BOOLEAN;
                case ITEM -> StepKind.STATIC_ITEM;
                case TAG -> StepKind.STATIC_TAG;
            };
        }

        boolean isAssignable(TypeInfo actualType, TypeInfo expectedType);
    }

    /** Registry-independent description of a registered Dynamic value type. */
    public record TypeInfo(String id, String displayName) {
    }

    /** Registry-independent description of one registered operator. */
    public record FunctionInfo(String operatorId, String displayName, List<TypeInfo> inputTypes,
                               TypeInfo outputType, int requiredInputLength) {
        public FunctionInfo {
            inputTypes = List.copyOf(inputTypes);
        }
    }

    public record Compilation(List<CardStep> steps, String rootId, List<StatementRoot> statementRoots,
                              Map<String, TypeInfo> virtualTypes, int errorPosition, String message) {
        static Compilation success(List<CardStep> steps, String rootId, List<StatementRoot> statementRoots,
                                   Map<String, TypeInfo> virtualTypes, String message) {
            return new Compilation(List.copyOf(steps), rootId, List.copyOf(statementRoots), Map.copyOf(virtualTypes),
                    -1, message);
        }

        static Compilation failure(int position, String message) {
            return new Compilation(List.of(), null, List.of(), Map.of(), position,
                    "Error (character " + (position + 1) + "): " + message);
        }

        public boolean valid() {
            return errorPosition < 0;
        }
    }

    /** A dependency-graph node; all kinds except external references create a Variable Card. */
    public record CardStep(String id, StepKind kind, String value, List<String> inputs, String outputTypeId,
                           int sourceStart, int sourceEnd) {
        public CardStep {
            inputs = List.copyOf(inputs);
        }

        public boolean createsVariableCard() {
            return kind != StepKind.EXTERNAL_REFERENCE;
        }
    }

    /** The top-level expression associated with one source statement. */
    public record StatementRoot(int sourceStart, int sourceEnd, String stepId) {
    }

    public enum StepKind {
        STATIC_TEXT, STATIC_MOD, STATIC_BOOLEAN, STATIC_ITEM, STATIC_FLUID, STATIC_TAG, DYNAMIC_OPERATOR,
        EXTERNAL_REFERENCE
    }

    public enum LiteralKind {
        STRING, INTEGER, DECIMAL, BOOLEAN, ITEM, MOD, TAG
    }
}
