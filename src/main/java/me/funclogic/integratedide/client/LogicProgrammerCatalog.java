package me.funclogic.integratedide.client;

import me.funclogic.integratedide.expr.ExpressionCompiler;
import me.funclogic.integratedide.expr.PartialCallAnalysis;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.fml.ModList;
import org.cyclops.integrateddynamics.api.evaluate.operator.IOperator;
import org.cyclops.integrateddynamics.api.evaluate.variable.IValueType;
import org.cyclops.integrateddynamics.core.evaluate.operator.Operators;
import org.cyclops.integrateddynamics.core.evaluate.variable.ValueTypes;

/**
 * Snapshot of the actual value types and callable operators exposed by this
 * client's Logic Programmer. No function, member-name, or object-type table
 * is maintained by this mod.
 */
public final class LogicProgrammerCatalog implements ExpressionCompiler.Catalog {
    /**
     * The optional operator metadata lets the UI show an accurate signature
     * without keeping a second, hand-written function table.
     */
    public record Completion(String insertion, String detail, ExpressionCompiler.FunctionInfo function,
                             int receiverArguments) {
        public Completion(String insertion, String detail) {
            this(insertion, detail, null, 0);
        }
    }

    /** A callable signature at the cursor in an otherwise incomplete expression. */
    public record Signature(String invocation, ExpressionCompiler.FunctionInfo function, int receiverArguments,
                            int activeArgument, boolean emptyArgument) {
        public ExpressionCompiler.TypeInfo expectedType() {
            int input = receiverArguments + activeArgument;
            return input >= 0 && input < function.inputTypes().size() ? function.inputTypes().get(input) : null;
        }
    }

    private final Map<String, IValueType<?>> valuesById = new LinkedHashMap<>();
    private final Map<String, ExpressionCompiler.TypeInfo> typesById = new LinkedHashMap<>();
    private final Map<String, ExpressionCompiler.TypeInfo> typesByName = new LinkedHashMap<>();
    private final Map<String, ExpressionCompiler.FunctionInfo> globals = new LinkedHashMap<>();
    private final Map<String, List<String>> globalFormsByMemberName = new LinkedHashMap<>();
    private final Map<String, Map<String, ExpressionCompiler.FunctionInfo>> members = new LinkedHashMap<>();
    private final List<Completion> resourceCompletions;
    private final List<Completion> modCompletions;
    private final List<Completion> tagCompletions;

    private LogicProgrammerCatalog() {
        for (IValueType<?> type : ValueTypes.REGISTRY.getValueTypes()) {
            ExpressionCompiler.TypeInfo info = new ExpressionCompiler.TypeInfo(type.getUniqueName().toString(),
                    type.getTypeName());
            valuesById.put(info.id(), type);
            typesById.put(info.id(), info);
            typesByName.putIfAbsent(type.getTypeName().toLowerCase(Locale.ROOT), info);
        }
        for (Map.Entry<String, IOperator> entry : Operators.REGISTRY.getGlobalInteractOperators().entrySet()) {
            IOperator operator = entry.getValue();
            globals.put(entry.getKey(), describe(operator, entry.getKey()));
            String memberName = operator.getScopedInteractName();
            if (!entry.getKey().equals(memberName)) {
                globalFormsByMemberName.computeIfAbsent(memberName, ignored -> new ArrayList<>()).add(entry.getKey());
            }
        }
        globalFormsByMemberName.replaceAll((ignored, names) -> names.stream().distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER).toList());
        for (Map.Entry<IValueType<?>, Map<String, IOperator>> entry : Operators.REGISTRY.getScopedInteractOperators().entrySet()) {
            String receiverId = entry.getKey().getUniqueName().toString();
            Map<String, ExpressionCompiler.FunctionInfo> functions = members.computeIfAbsent(receiverId,
                    ignored -> new LinkedHashMap<>());
            for (Map.Entry<String, IOperator> function : entry.getValue().entrySet()) {
                functions.put(function.getKey(), describe(function.getValue(), function.getKey()));
            }
        }
        resourceCompletions = buildResourceCompletions();
        modCompletions = buildModCompletions();
        tagCompletions = buildTagCompletions();
    }

    public static LogicProgrammerCatalog create() {
        return new LogicProgrammerCatalog();
    }

    @Override
    public ExpressionCompiler.FunctionInfo globalFunction(String name) {
        return globals.get(name);
    }

    @Override
    public String missingGlobalFunctionHint(String name) {
        List<String> globalForms = globalFormsByMemberName.get(name);
        if (globalForms == null || globalForms.isEmpty()) {
            return null;
        }
        String rendered = String.join(", ", globalForms.stream()
                .map(candidate -> candidate + "(...)")
                .toList());
        return "'" + name + "' is a member function name; use " + rendered
                + " for the global form, or <object>." + name + "(...).";
    }

    @Override
    public ExpressionCompiler.FunctionInfo memberFunction(ExpressionCompiler.TypeInfo receiverType, String name) {
        return callableMembers(receiverType).get(name);
    }

    @Override
    public ExpressionCompiler.TypeInfo literalType(ExpressionCompiler.LiteralKind kind, String value,
                                                    ExpressionCompiler.TypeInfo expectedType) {
        return switch (kind) {
            case STRING, MOD -> typeNamed("string");
            case BOOLEAN -> typeNamed("boolean");
            case INTEGER -> numericType(expectedType, "integer");
            case DECIMAL -> numericType(expectedType, "double");
            case ITEM -> resolveResourceType(value, expectedType);
            case TAG -> typeNamed("ingredients");
        };
    }

    @Override
    public boolean isAssignable(ExpressionCompiler.TypeInfo actualType, ExpressionCompiler.TypeInfo expectedType) {
        if (expectedType == null) {
            return true;
        }
        IValueType<?> actual = valuesById.get(actualType.id());
        IValueType<?> expected = valuesById.get(expectedType.id());
        // Integrated Dynamics categories (such as any and number) accept their
        // concrete members, so compatibility is defined by the required type.
        return actual != null && expected != null && expected.correspondsTo(actual);
    }

    public ExpressionCompiler.Compilation compile(String source) {
        return ExpressionCompiler.compile(source, this);
    }

    public Signature signatureAt(String source, int cursor) {
        if (PartialCallAnalysis.isInLineComment(source, cursor)) {
            return null;
        }
        return PartialCallAnalysis.at(source, cursor).map(call -> {
            ExpressionCompiler.FunctionInfo function;
            int receiverArguments;
            String invocation;
            if (call.receiver() == null) {
                function = globalFunction(call.name());
                receiverArguments = 0;
                invocation = call.name();
            } else {
                ExpressionCompiler.TypeInfo receiverType = virtualType(beforeCursor(source, cursor), call.receiver());
                function = receiverType == null ? null : memberFunction(receiverType, call.name());
                receiverArguments = 1;
                invocation = call.receiver() + "." + call.name();
            }
            return function == null ? null : new Signature(invocation, function, receiverArguments,
                    call.argumentIndex(), call.emptyArgument());
        }).orElse(null);
    }

    public boolean hasAutomaticCompletionTrigger(String source, int cursor) {
        if (PartialCallAnalysis.isInLineComment(source, cursor)) {
            return false;
        }
        String token = currentToken(beforeCursor(source, cursor));
        if (token.startsWith("\"$") || token.startsWith("\"@") || token.startsWith("\"#")) {
            return token.length() > 2;
        }
        if (token.lastIndexOf('.') > 0) {
            return true;
        }
        return !token.isEmpty() && isIdentifierStart(token.charAt(0));
    }

    public List<Completion> completions(String source, int cursor, ExpressionCompiler.TypeInfo expectedType,
                                        boolean explicitlyRequested) {
        if (PartialCallAnalysis.isInLineComment(source, cursor)) {
            return List.of();
        }
        String beforeCursor = beforeCursor(source, cursor);
        String token = currentToken(beforeCursor);
        if (token.startsWith("\"$")) {
            String prefix = token.substring(2);
            return prefix.isEmpty() && !explicitlyRequested ? List.of() : resourceCompletions(prefix, expectedType);
        }
        if (token.startsWith("\"@")) {
            String prefix = token.substring(2);
            return prefix.isEmpty() && !explicitlyRequested ? List.of() : modCompletions(prefix, expectedType);
        }
        if (token.startsWith("\"#")) {
            String prefix = token.substring(2);
            return prefix.isEmpty() && !explicitlyRequested ? List.of() : tagCompletions(prefix, expectedType);
        }
        int dot = token.lastIndexOf('.');
        if (dot > 0) {
            String receiver = token.substring(0, dot);
            String prefix = token.substring(dot + 1);
            ExpressionCompiler.TypeInfo receiverType = virtualType(beforeCursor, receiver);
            if (receiverType != null) {
                return memberCompletions(receiverType, prefix, token.substring(0, dot + 1), expectedType);
            }
        }
        if (token.isEmpty() && !explicitlyRequested) {
            return List.of();
        }
        return globalCompletions(token, expectedType);
    }

    private static String beforeCursor(String source, int cursor) {
        String input = source == null ? "" : source;
        return input.substring(0, Math.max(0, Math.min(cursor, input.length())));
    }

    private ExpressionCompiler.TypeInfo virtualType(String sourceBeforeCursor, String receiver) {
        if (!receiver.startsWith("{") || !receiver.endsWith("}")) {
            return null;
        }
        String name = receiver.substring(1, receiver.length() - 1);
        int lineStart = Math.max(sourceBeforeCursor.lastIndexOf('\n'), sourceBeforeCursor.lastIndexOf('\r')) + 1;
        String completedLines = sourceBeforeCursor.substring(0, lineStart);
        ExpressionCompiler.Compilation compilation = compile(completedLines);
        return compilation.valid() ? compilation.virtualTypes().get(name) : null;
    }

    private List<Completion> globalCompletions(String prefix, ExpressionCompiler.TypeInfo expectedType) {
        return globals.entrySet().stream()
                .filter(entry -> startsWithIgnoreCase(entry.getKey(), prefix))
                .filter(entry -> produces(entry.getValue(), expectedType))
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .limit(12)
                .map(entry -> new Completion(entry.getKey() + "(", "global · " + entry.getValue().operatorId(),
                        entry.getValue(), 0))
                .toList();
    }

    private List<Completion> memberCompletions(ExpressionCompiler.TypeInfo receiver, String prefix, String insertionPrefix,
                                               ExpressionCompiler.TypeInfo expectedType) {
        Map<String, ExpressionCompiler.FunctionInfo> candidates = callableMembers(receiver);
        return candidates.entrySet().stream()
                .filter(entry -> startsWithIgnoreCase(entry.getKey(), prefix))
                .filter(entry -> produces(entry.getValue(), expectedType))
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .limit(12)
                .map(entry -> new Completion(insertionPrefix + entry.getKey() + "(", "member · "
                        + entry.getValue().operatorId(), entry.getValue(), 1))
                .toList();
    }

    /** Keeps member compilation and member completion on the same Dynamic type hierarchy rule. */
    private Map<String, ExpressionCompiler.FunctionInfo> callableMembers(ExpressionCompiler.TypeInfo receiverType) {
        IValueType<?> actual = valuesById.get(receiverType.id());
        if (actual == null) {
            return Map.of();
        }
        Map<String, ExpressionCompiler.FunctionInfo> candidates = new LinkedHashMap<>();
        Map<String, ExpressionCompiler.FunctionInfo> exact = members.get(receiverType.id());
        if (exact != null) {
            candidates.putAll(exact);
        }
        for (Map.Entry<String, Map<String, ExpressionCompiler.FunctionInfo>> entry : members.entrySet()) {
            IValueType<?> scope = valuesById.get(entry.getKey());
            if (scope != null && scope.correspondsTo(actual)) {
                entry.getValue().forEach(candidates::putIfAbsent);
            }
        }
        return candidates;
    }

    private List<Completion> resourceCompletions(String prefix, ExpressionCompiler.TypeInfo expectedType) {
        return matching(resourceCompletions, prefix, entry -> producesResource(entry, expectedType));
    }

    private List<Completion> modCompletions(String prefix, ExpressionCompiler.TypeInfo expectedType) {
        return matching(modCompletions, prefix,
                entry -> expectedType == null || produces(typeNamed("string"), expectedType));
    }

    private List<Completion> tagCompletions(String prefix, ExpressionCompiler.TypeInfo expectedType) {
        return matching(tagCompletions, prefix,
                entry -> expectedType == null || produces(typeNamed("ingredients"), expectedType));
    }

    private boolean produces(ExpressionCompiler.FunctionInfo function, ExpressionCompiler.TypeInfo expectedType) {
        return expectedType == null || produces(function.outputType(), expectedType);
    }

    private boolean producesResource(Completion completion, ExpressionCompiler.TypeInfo expectedType) {
        if (expectedType == null) {
            return true;
        }
        ExpressionCompiler.TypeInfo actual = completion.detail().equals("fluid") ? typeNamed("fluidstack")
                : typeNamed("itemstack");
        return produces(actual, expectedType);
    }

    private boolean produces(ExpressionCompiler.TypeInfo actual, ExpressionCompiler.TypeInfo expectedType) {
        return actual != null && isAssignable(actual, expectedType);
    }

    private List<Completion> buildResourceCompletions() {
        List<Completion> results = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.FLUID.keySet()) {
            results.add(new Completion("\"$" + id + "\"", "fluid"));
        }
        for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
            results.add(new Completion("\"$" + id + "\"", "item"));
        }
        return distinctAndSorted(results);
    }

    private List<Completion> buildModCompletions() {
        return ModList.get().getMods().stream()
                .sorted(Comparator.comparing(mod -> mod.getModId(), String.CASE_INSENSITIVE_ORDER))
                .map(mod -> new Completion("\"@" + mod.getModId() + "\"", mod.getDisplayName()))
                .toList();
    }

    private List<Completion> buildTagCompletions() {
        Set<String> ids = new LinkedHashSet<>();
        BuiltInRegistries.ITEM.getTags().forEach(tag -> ids.add(tag.key().location().toString()));
        BuiltInRegistries.FLUID.getTags().forEach(tag -> ids.add(tag.key().location().toString()));
        return ids.stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .map(id -> new Completion("\"#" + id + "\"", "item/fluid tag"))
                .toList();
    }

    private List<Completion> matching(List<Completion> candidates, String prefix, Predicate<Completion> allowed) {
        return candidates.stream()
                .filter(entry -> startsWithIgnoreCase(resourceId(entry.insertion()), prefix)
                        || (entry.insertion().startsWith("\"@") && startsWithIgnoreCase(entry.detail(), prefix)))
                .filter(allowed)
                .limit(12)
                .toList();
    }

    private List<Completion> distinctAndSorted(List<Completion> entries) {
        Map<String, Completion> unique = new LinkedHashMap<>();
        for (Completion entry : entries) {
            unique.putIfAbsent(entry.insertion(), entry);
        }
        return unique.values().stream()
                .sorted(Comparator.comparing(Completion::insertion, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static String resourceId(String insertion) {
        return insertion.substring(2, insertion.length() - 1);
    }

    private ExpressionCompiler.FunctionInfo describe(IOperator operator, String displayName) {
        List<ExpressionCompiler.TypeInfo> inputs = new ArrayList<>();
        for (IValueType<?> input : operator.getInputTypes()) {
            inputs.add(typeOf(input));
        }
        return new ExpressionCompiler.FunctionInfo(operator.getUniqueName().toString(), displayName, inputs,
                typeOf(operator.getOutputType()), operator.getRequiredInputLength());
    }

    private ExpressionCompiler.TypeInfo resolveResourceType(String value, ExpressionCompiler.TypeInfo expected) {
        Identifier id = Identifier.tryParse(value);
        if (id != null && BuiltInRegistries.FLUID.containsKey(id)) {
            return typeNamed("fluidstack");
        }
        if (expected != null) {
            String name = expected.displayName().toLowerCase(Locale.ROOT);
            if (name.equals("fluidstack") || name.equals("itemstack")) {
                return expected;
            }
        }
        return typeNamed("itemstack");
    }

    private ExpressionCompiler.TypeInfo numericType(ExpressionCompiler.TypeInfo expected, String fallback) {
        if (expected != null) {
            String name = expected.displayName().toLowerCase(Locale.ROOT);
            if (name.equals("integer") || name.equals("long") || name.equals("double")) {
                return expected;
            }
        }
        return typeNamed(fallback);
    }

    private ExpressionCompiler.TypeInfo typeNamed(String name) {
        return typesByName.get(name);
    }

    private ExpressionCompiler.TypeInfo typeOf(IValueType<?> type) {
        return typesById.get(type.getUniqueName().toString());
    }

    private static String currentToken(String source) {
        int start = source.length();
        while (start > 0 && isCompletionCharacter(source.charAt(start - 1))) {
            start--;
        }
        return source.substring(start);
    }

    private static boolean isCompletionCharacter(char character) {
        return Character.isLetterOrDigit(character) || character == '_' || character == '-' || character == ':'
                || character == '/' || character == '.' || character == '$' || character == '@' || character == '#'
                || character == '"'
                || character == '{' || character == '}';
    }

    private static boolean isIdentifierStart(char character) {
        return character == '_' || Character.isLetter(character);
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
