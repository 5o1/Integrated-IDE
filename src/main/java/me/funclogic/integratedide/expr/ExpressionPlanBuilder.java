package me.funclogic.integratedide.expr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lowers a parsed, type-checked expression program to ordinary Variable Card steps. */
final class ExpressionPlanBuilder {
    private static final ExpressionCompiler.TypeInfo EXTERNAL_TYPE =
            new ExpressionCompiler.TypeInfo("integratedide:external", "external Variable Card");
    private final ExpressionCompiler.Catalog catalog;
    private final List<ExpressionCompiler.CardStep> steps = new ArrayList<>();
    private final Map<String, PlanValue> virtualValues = new LinkedHashMap<>();
    private final Map<String, ExpressionCompiler.TypeInfo> virtualTypes = new LinkedHashMap<>();
    private int nextId;

    ExpressionPlanBuilder(ExpressionCompiler.Catalog catalog) {
        this.catalog = catalog;
    }

    ExpressionCompiler.Compilation compile(ExpressionSyntax.Program program) {
        LoweredProgram lowered = lower(program);
        int created = (int) steps.stream().filter(ExpressionCompiler.CardStep::createsVariableCard).count();
        return ExpressionCompiler.Compilation.success(steps, lowered.rootId(), lowered.statementRoots(), virtualTypes,
                "Valid: " + created + " Variable Card(s) will be created.");
    }

    private LoweredProgram lower(ExpressionSyntax.Program program) {
        if (program.statements().isEmpty()) {
            throw new ExpressionCompileError(0, "Enter one or more statements.");
        }
        String root = null;
        List<ExpressionCompiler.StatementRoot> statementRoots = new ArrayList<>();
        for (ExpressionSyntax.Statement statement : program.statements()) {
            if (statement instanceof ExpressionSyntax.Assignment assignment) {
                if (virtualValues.containsKey(assignment.name())) {
                    throw new ExpressionCompileError(assignment.position(), "Temporary variable {" + assignment.name()
                            + "} is already defined.");
                }
                PlanValue value = lower(assignment.expression(), null);
                PlanValue virtualValue = value.withDuplicableDefinition(assignment.expression());
                virtualValues.put(assignment.name(), virtualValue);
                virtualTypes.put(assignment.name(), virtualValue.type());
                root = virtualValue.id();
                statementRoots.add(new ExpressionCompiler.StatementRoot(assignment.position(), assignment.end(), root));
            } else if (statement instanceof ExpressionSyntax.ExpressionStatement expressionStatement) {
                root = lower(expressionStatement.expression(), null).id();
                statementRoots.add(new ExpressionCompiler.StatementRoot(expressionStatement.position(),
                        expressionStatement.end(), root));
            }
        }
        return new LoweredProgram(root, List.copyOf(statementRoots));
    }

    private PlanValue lower(ExpressionSyntax.Expr expression, ExpressionCompiler.TypeInfo expectedType) {
        PlanValue value = switch (expression) {
            case ExpressionSyntax.Literal literal -> lowerLiteral(literal, expectedType);
            case ExpressionSyntax.Reference reference -> lowerReference(reference);
            case ExpressionSyntax.ExternalReference reference -> lowerExternalReference(reference, expectedType);
            case ExpressionSyntax.GlobalCall call -> lowerGlobalCall(call);
            case ExpressionSyntax.MemberCall call -> lowerMemberCall(call);
        };
        if (expectedType != null && !catalog.isAssignable(value.type(), expectedType)) {
            throw new ExpressionCompileError(expression.position(), "Expected " + expectedType.displayName()
                    + ", but this expression produces " + value.type().displayName() + ".");
        }
        return value;
    }

    private PlanValue lowerLiteral(ExpressionSyntax.Literal literal, ExpressionCompiler.TypeInfo expectedType) {
        ExpressionCompiler.TypeInfo type = catalog.literalType(literal.kind(), literal.value(), expectedType);
        if (type == null) {
            throw new ExpressionCompileError(literal.position(), "This literal cannot be converted to the required type.");
        }
        ExpressionCompiler.StepKind kind = switch (literal.kind()) {
            case STRING, INTEGER, DECIMAL -> ExpressionCompiler.StepKind.STATIC_TEXT;
            case MOD -> ExpressionCompiler.StepKind.STATIC_MOD;
            case BOOLEAN -> ExpressionCompiler.StepKind.STATIC_BOOLEAN;
            case ITEM -> ExpressionCompiler.StepKind.STATIC_ITEM;
            case TAG -> ExpressionCompiler.StepKind.STATIC_TAG;
        };
        if (literal.kind() == ExpressionCompiler.LiteralKind.ITEM && type.displayName().equalsIgnoreCase("fluidstack")) {
            kind = ExpressionCompiler.StepKind.STATIC_FLUID;
        }
        return add(kind, literal.value(), List.of(), type, literal.position(), literal.end());
    }

    private PlanValue lowerReference(ExpressionSyntax.Reference reference) {
        PlanValue value = virtualValues.get(reference.name());
        if (value == null) {
            throw new ExpressionCompileError(reference.position(), "Temporary variable {" + reference.name()
                    + "} has not been defined on an earlier line.");
        }
        return value;
    }

    private PlanValue lowerExternalReference(ExpressionSyntax.ExternalReference reference,
                                             ExpressionCompiler.TypeInfo expectedType) {
        if (reference.variableCardId() < 0) {
            throw new ExpressionCompileError(reference.position(), "Variable Card IDs cannot be negative.");
        }
        ExpressionCompiler.TypeInfo type = expectedType == null ? EXTERNAL_TYPE : expectedType;
        return add(ExpressionCompiler.StepKind.EXTERNAL_REFERENCE, Integer.toString(reference.variableCardId()), List.of(),
                type, reference.position(), reference.end(), reference.variableCardId());
    }

    private PlanValue lowerGlobalCall(ExpressionSyntax.GlobalCall call) {
        ExpressionCompiler.FunctionInfo function = catalog.globalFunction(call.name());
        if (function == null) {
            String hint = catalog.missingGlobalFunctionHint(call.name());
            throw new ExpressionCompileError(call.position(), "No registered global function named '" + call.name()
                    + "'." + (hint == null ? "" : " " + hint));
        }
        return lowerCall(call.position(), call.end(), function, null, call.arguments());
    }

    private PlanValue lowerMemberCall(ExpressionSyntax.MemberCall call) {
        PlanValue receiver = lower(call.receiver(), null);
        ExpressionCompiler.FunctionInfo function = catalog.memberFunction(receiver.type(), call.name());
        if (function == null) {
            throw new ExpressionCompileError(call.position(), "Type " + receiver.type().displayName()
                    + " has no registered member function '" + call.name() + "'.");
        }
        return lowerCall(call.position(), call.end(), function, receiver, call.arguments());
    }

    private PlanValue lowerCall(int position, int end, ExpressionCompiler.FunctionInfo function, PlanValue receiver,
                                List<ExpressionSyntax.Expr> arguments) {
        int supplied = arguments.size() + (receiver == null ? 0 : 1);
        if (supplied < function.requiredInputLength() || supplied > function.inputTypes().size()) {
            throw new ExpressionCompileError(position, function.displayName() + " expects " + function.requiredInputLength()
                    + " to " + function.inputTypes().size() + " argument(s), but received " + supplied + ".");
        }
        List<PlanValue> inputValues = new ArrayList<>();
        int offset = 0;
        if (receiver != null) {
            if (!catalog.isAssignable(receiver.type(), function.inputTypes().getFirst())) {
                throw new ExpressionCompileError(position, "The receiver is not compatible with "
                        + function.inputTypes().getFirst().displayName() + ".");
            }
            inputValues.add(receiver);
            offset = 1;
        }
        for (int index = 0; index < arguments.size(); index++) {
            inputValues.add(lower(arguments.get(index), function.inputTypes().get(index + offset)));
        }
        List<String> inputs = independentInputIds(inputValues, function, position);
        return add(ExpressionCompiler.StepKind.DYNAMIC_OPERATOR, function.operatorId(), inputs, function.outputType(),
                position, end);
    }

    /**
     * The Logic Programmer moves each input card out of the player inventory
     * before placing it.  A plan may therefore not put one physical card into
     * two inputs of the same operator.  Virtual values can safely be expanded
     * here; external IDs cannot because this client has no authority to clone
     * an existing server-side Variable Card.
     */
    private List<String> independentInputIds(List<PlanValue> inputValues, ExpressionCompiler.FunctionInfo function,
                                             int position) {
        Map<String, Integer> uses = new LinkedHashMap<>();
        List<String> inputs = new ArrayList<>();
        for (PlanValue value : inputValues) {
            String physicalKey = value.externalCardId() == null ? value.id() : "external:" + value.externalCardId();
            int use = uses.merge(physicalKey, 1, Integer::sum);
            if (use == 1) {
                inputs.add(value.id());
                continue;
            }
            if (value.externalCardId() != null) {
                throw new ExpressionCompileError(position, "External Variable Card {" + value.externalCardId()
                        + "} is used more than once by " + function.displayName()
                        + ". Each Logic Programmer input needs a separate physical card.");
            }
            ExpressionSyntax.Expr definition = value.duplicableDefinition();
            if (definition == null) {
                throw new ExpressionCompileError(position, "Temporary value " + value.id()
                        + " cannot be used more than once by " + function.displayName() + ".");
            }
            inputs.add(lower(definition, null).id());
        }
        return inputs;
    }

    private PlanValue add(ExpressionCompiler.StepKind kind, String value, List<String> inputs,
                          ExpressionCompiler.TypeInfo outputType, int sourceStart, int sourceEnd) {
        return add(kind, value, inputs, outputType, sourceStart, sourceEnd, null);
    }

    private PlanValue add(ExpressionCompiler.StepKind kind, String value, List<String> inputs,
                          ExpressionCompiler.TypeInfo outputType, int sourceStart, int sourceEnd,
                          Integer externalCardId) {
        String id = "v" + nextId++;
        steps.add(new ExpressionCompiler.CardStep(id, kind, value, inputs, outputType.id(), sourceStart, sourceEnd));
        return new PlanValue(id, outputType, externalCardId, null);
    }

    private record PlanValue(String id, ExpressionCompiler.TypeInfo type, Integer externalCardId,
                             ExpressionSyntax.Expr duplicableDefinition) {
        /** Preserve the original expression when a temporary is merely an alias of another temporary. */
        private PlanValue withDuplicableDefinition(ExpressionSyntax.Expr definition) {
            return duplicableDefinition == null
                    ? new PlanValue(id, type, externalCardId, definition)
                    : this;
        }
    }

    private record LoweredProgram(String rootId, List<ExpressionCompiler.StatementRoot> statementRoots) {
    }
}
