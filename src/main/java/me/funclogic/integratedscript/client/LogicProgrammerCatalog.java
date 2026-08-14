package me.funclogic.integratedscript.client;

import me.funclogic.integratedscript.expr.ExpressionCompiler;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    private final Map<String, IValueType<?>> valuesById = new LinkedHashMap<>();
    private final Map<String, ExpressionCompiler.TypeInfo> typesById = new LinkedHashMap<>();
    private final Map<String, ExpressionCompiler.TypeInfo> typesByName = new LinkedHashMap<>();
    private final Map<String, ExpressionCompiler.FunctionInfo> globals = new LinkedHashMap<>();
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
            globals.put(entry.getKey(), describe(entry.getValue(), entry.getKey()));
        }
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
    public ExpressionCompiler.FunctionInfo memberFunction(ExpressionCompiler.TypeInfo receiverType, String name) {
        IValueType<?> actual = valuesById.get(receiverType.id());
        if (actual == null) {
            return null;
        }
        Map<String, ExpressionCompiler.FunctionInfo> exact = members.get(receiverType.id());
        if (exact != null && exact.containsKey(name)) {
            return exact.get(name);
        }
        for (Map.Entry<String, Map<String, ExpressionCompiler.FunctionInfo>> entry : members.entrySet()) {
            IValueType<?> scope = valuesById.get(entry.getKey());
            if (scope != null && actual.correspondsTo(scope) && entry.getValue().containsKey(name)) {
                return entry.getValue().get(name);
            }
        }
        return null;
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
        return actual != null && expected != null && actual.correspondsTo(expected);
    }

    public ExpressionCompiler.Compilation compile(String source) {
        return ExpressionCompiler.compile(source, this);
    }

    public List<Completion> completions(String source, int cursor) {
        String beforeCursor = source.substring(0, Math.max(0, Math.min(cursor, source.length())));
        String token = currentToken(beforeCursor);
        if (token.startsWith("$")) {
            return resourceCompletions(token.substring(1));
        }
        if (token.startsWith("@")) {
            return modCompletions(token.substring(1));
        }
        if (token.startsWith("#")) {
            return tagCompletions(token.substring(1));
        }
        int dot = token.lastIndexOf('.');
        if (dot > 0) {
            String receiver = token.substring(0, dot);
            String prefix = token.substring(dot + 1);
            ExpressionCompiler.TypeInfo receiverType = virtualType(beforeCursor, receiver);
            if (receiverType != null) {
                return memberCompletions(receiverType, prefix, token.substring(0, dot + 1));
            }
        }
        return globalCompletions(token);
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

    private List<Completion> globalCompletions(String prefix) {
        return globals.entrySet().stream()
                .filter(entry -> startsWithIgnoreCase(entry.getKey(), prefix))
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .limit(12)
                .map(entry -> new Completion(entry.getKey() + "(", "global · " + entry.getValue().operatorId(),
                        entry.getValue(), 0))
                .toList();
    }

    private List<Completion> memberCompletions(ExpressionCompiler.TypeInfo receiver, String prefix, String insertionPrefix) {
        Map<String, ExpressionCompiler.FunctionInfo> candidates = new LinkedHashMap<>();
        Map<String, ExpressionCompiler.FunctionInfo> exact = members.get(receiver.id());
        if (exact != null) {
            candidates.putAll(exact);
        }
        IValueType<?> actual = valuesById.get(receiver.id());
        if (actual != null) {
            for (Map.Entry<String, Map<String, ExpressionCompiler.FunctionInfo>> entry : members.entrySet()) {
                IValueType<?> scope = valuesById.get(entry.getKey());
                if (scope != null && actual.correspondsTo(scope)) {
                    entry.getValue().forEach(candidates::putIfAbsent);
                }
            }
        }
        return candidates.entrySet().stream()
                .filter(entry -> startsWithIgnoreCase(entry.getKey(), prefix))
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .limit(12)
                .map(entry -> new Completion(insertionPrefix + entry.getKey() + "(", "member · "
                        + entry.getValue().operatorId(), entry.getValue(), 1))
                .toList();
    }

    private List<Completion> resourceCompletions(String prefix) {
        return matching(resourceCompletions, prefix);
    }

    private List<Completion> modCompletions(String prefix) {
        return matching(modCompletions, prefix);
    }

    private List<Completion> tagCompletions(String prefix) {
        return matching(tagCompletions, prefix);
    }

    private List<Completion> buildResourceCompletions() {
        List<Completion> results = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.FLUID.keySet()) {
            results.add(new Completion("$" + id, "fluid"));
        }
        for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
            results.add(new Completion("$" + id, "item"));
        }
        return distinctAndSorted(results);
    }

    private List<Completion> buildModCompletions() {
        return ModList.get().getMods().stream()
                .sorted(Comparator.comparing(mod -> mod.getModId(), String.CASE_INSENSITIVE_ORDER))
                .map(mod -> new Completion("@" + mod.getModId(), mod.getDisplayName()))
                .toList();
    }

    private List<Completion> buildTagCompletions() {
        Set<String> ids = new LinkedHashSet<>();
        BuiltInRegistries.ITEM.getTags().forEach(tag -> ids.add(tag.key().location().toString()));
        BuiltInRegistries.FLUID.getTags().forEach(tag -> ids.add(tag.key().location().toString()));
        return ids.stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .map(id -> new Completion("#" + id, "item/fluid tag"))
                .toList();
    }

    private List<Completion> matching(List<Completion> candidates, String prefix) {
        return candidates.stream()
                .filter(entry -> startsWithIgnoreCase(entry.insertion().substring(1), prefix)
                        || (entry.insertion().startsWith("@") && startsWithIgnoreCase(entry.detail(), prefix)))
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
                || character == '{' || character == '}';
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
