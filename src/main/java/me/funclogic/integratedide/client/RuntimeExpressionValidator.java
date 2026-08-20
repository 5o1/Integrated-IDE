package me.funclogic.integratedide.client;

import java.util.HashMap;
import java.util.Map;
import me.funclogic.integratedide.expr.ExpressionCompiler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.neoforged.fml.ModList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.cyclops.integrateddynamics.api.evaluate.operator.IOperator;
import org.cyclops.integrateddynamics.api.evaluate.variable.IValueType;
import org.cyclops.integrateddynamics.core.evaluate.operator.Operators;
import org.cyclops.integrateddynamics.core.evaluate.variable.ValueTypes;

/** Re-checks client registries immediately before sending normal GUI packets. */
public final class RuntimeExpressionValidator {
    private RuntimeExpressionValidator() {
    }

    public static Result validate(ExpressionCompiler.Compilation compilation) {
        if (!compilation.valid()) {
            return Result.failure(compilation.message());
        }
        for (ExpressionCompiler.CardStep step : compilation.steps()) {
            if (step.kind() == ExpressionCompiler.StepKind.EXTERNAL_REFERENCE) {
                // External cards are validated against the live inventory by
                // the editor immediately before a build starts.
                continue;
            }
            Identifier outputType = Identifier.tryParse(step.outputTypeId());
            if (outputType == null || ValueTypes.REGISTRY.getValueType(outputType) == null) {
                return Result.failure("The current client no longer registers value type " + step.outputTypeId() + ".");
            }
            switch (step.kind()) {
                case DYNAMIC_OPERATOR -> {
                    Identifier operator = Identifier.tryParse(step.value());
                    if (operator == null || Operators.REGISTRY.getOperator(operator) == null) {
                        return Result.failure("The current client no longer registers operator " + step.value() + ".");
                    }
                }
                case STATIC_ITEM -> {
                    Identifier item = Identifier.tryParse(step.value());
                    if (item == null || !BuiltInRegistries.ITEM.containsKey(item)) {
                        return Result.failure("Unknown item identifier: $" + step.value());
                    }
                }
                case STATIC_FLUID -> {
                    Identifier fluid = Identifier.tryParse(step.value());
                    if (fluid == null || !BuiltInRegistries.FLUID.containsKey(fluid)) {
                        return Result.failure("Unknown fluid identifier: $" + step.value());
                    }
                    try {
                        CardLiteralFactory.fluidBucket(step.value());
                    } catch (IllegalArgumentException error) {
                        return Result.failure(error.getMessage());
                    }
                }
                case STATIC_TAG -> {
                    Identifier tag = Identifier.tryParse(step.value());
                    if (tag == null || (!BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, tag)).iterator().hasNext()
                            && !BuiltInRegistries.FLUID.getTagOrEmpty(TagKey.create(Registries.FLUID, tag)).iterator().hasNext())) {
                        return Result.failure("No item or fluid tag named #" + step.value() + " is loaded on this client.");
                    }
                }
                case STATIC_MOD -> {
                    if (!ModList.get().isLoaded(step.value())) {
                        return Result.failure("The current client does not load mod @" + step.value() + ".");
                    }
                }
                case STATIC_TEXT -> {
                    // Generic scalar text is checked by the active value type on the server.
                }
                case STATIC_BOOLEAN -> {
                    // Lexing already guarantees true or false.
                }
            }
        }
        return validateOperatorInputTypes(compilation, Map.of());
    }

    /**
     * Revalidates Dynamic operator constraints after cache and external-card
     * lookup. A numeric {@code {id}} has no static output type, whereas its
     * actual inventory card does; this is the earliest point at which Dynamic
     * can reject heterogeneous generic inputs before any GUI action is sent.
     */
    public static Result validateSelectedInputs(ExpressionCompiler.Compilation compilation,
                                                Map<String, ItemStack> availableCards) {
        Result registry = validate(compilation);
        if (!registry.valid()) {
            return registry;
        }
        return validateOperatorInputTypes(compilation, availableCards);
    }

    private static Result validateOperatorInputTypes(ExpressionCompiler.Compilation compilation,
                                                     Map<String, ItemStack> availableCards) {
        Map<String, IValueType<?>> producedTypes = new HashMap<>();
        for (ExpressionCompiler.CardStep step : compilation.steps()) {
            if (step.kind() == ExpressionCompiler.StepKind.EXTERNAL_REFERENCE) {
                ItemStack card = availableCards.get(step.id());
                producedTypes.put(step.id(), card == null ? null : CardInventory.variableCardOutputType(card));
                continue;
            }
            Identifier outputTypeId = Identifier.tryParse(step.outputTypeId());
            IValueType<?> outputType = outputTypeId == null ? null : ValueTypes.REGISTRY.getValueType(outputTypeId);
            if (outputType == null) {
                return Result.failure("The current client no longer registers value type " + step.outputTypeId() + ".");
            }
            if (step.kind() == ExpressionCompiler.StepKind.DYNAMIC_OPERATOR) {
                Identifier operatorId = Identifier.tryParse(step.value());
                IOperator operator = operatorId == null ? null : Operators.REGISTRY.getOperator(operatorId);
                if (operator == null) {
                    return Result.failure("The current client no longer registers operator " + step.value() + ".");
                }
                IValueType<?>[] inputs = new IValueType<?>[step.inputs().size()];
                boolean hasUnknownInput = false;
                for (int index = 0; index < inputs.length; index++) {
                    inputs[index] = producedTypes.get(step.inputs().get(index));
                    hasUnknownInput |= inputs[index] == null;
                }
                if (!hasUnknownInput) {
                    Component error = operator.validateTypes(inputs);
                    if (error != null) {
                        return Result.failure(error.getString());
                    }
                }
            }
            producedTypes.put(step.id(), outputType);
        }
        return Result.success("Live registry and Dynamic operator checks passed.");
    }

    public record Result(boolean valid, String message) {
        static Result success(String message) {
            return new Result(true, message);
        }

        static Result failure(String message) {
            return new Result(false, message);
        }
    }
}
