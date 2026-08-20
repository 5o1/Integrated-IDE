package me.funclogic.integratedide.client;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.cyclops.integrateddynamics.api.evaluate.variable.ValueDeseralizationContext;
import org.cyclops.integrateddynamics.api.evaluate.variable.IValueType;
import org.cyclops.integrateddynamics.api.item.IVariableFacade;
import org.cyclops.integrateddynamics.core.evaluate.variable.ValueTypes;
import org.cyclops.integrateddynamics.inventory.container.ContainerLogicProgrammerBase;
import org.cyclops.integrateddynamics.item.ItemVariable;

/** Inventory-only operations used by the card build state machine and UI. */
final class CardInventory {
    private CardInventory() {
    }

    static int findBlankVariableSlot(ContainerLogicProgrammerBase menu, Player player) {
        for (int index = 0; index < menu.slots.size(); index++) {
            Slot slot = menu.slots.get(index);
            if (slot.container == player.getInventory() && isBlankVariable(slot.getItem())) {
                return index;
            }
        }
        return -1;
    }

    /** Finds a valid Variable Card in a currently visible player slot by its Dynamic ID. */
    static int findVariableCardSlotById(ContainerLogicProgrammerBase menu, Player player, int variableCardId) {
        if (variableCardId < 0) {
            return -1;
        }
        for (int index = 0; index < menu.slots.size(); index++) {
            Slot slot = menu.slots.get(index);
            if (slot.container == player.getInventory() && variableCardId(slot.getItem()) == variableCardId) {
                return index;
            }
        }
        return -1;
    }

    static ItemStack findVariableCardById(Player player, int variableCardId, String expectedTypeId) {
        Identifier expectedId = Identifier.tryParse(expectedTypeId);
        IValueType<?> expectedType = expectedId == null ? null : ValueTypes.REGISTRY.getValueType(expectedId);
        return findVariableCardByIdWithType(player, variableCardId, expectedType);
    }

    /**
     * Locates a valid Variable Card by Dynamic's stable per-save ID. This is
     * deliberately not an ItemStack-component comparison: a programmer reset
     * can rebuild a client-side element while the authoritative returned card
     * is being synchronized.
     */
    static ItemStack findVariableCardById(Player player, int variableCardId) {
        return findVariableCardByIdWithType(player, variableCardId, null);
    }

    private static ItemStack findVariableCardByIdWithType(Player player, int variableCardId, IValueType<?> expectedType) {
        if (player == null || variableCardId < 0) {
            return null;
        }
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            IVariableFacade facade = variableFacade(stack);
            if (facade != null && facade.isValid() && facade.getId() == variableCardId
                    && matchesExpectedType(facade.getOutputType(), expectedType)) {
                return stack;
            }
        }
        return null;
    }

    static int variableCardId(ItemStack stack) {
        IVariableFacade facade = variableFacade(stack);
        return facade != null && facade.isValid() ? facade.getId() : -1;
    }

    /** Returns the live Dynamic output type carried by a valid Variable Card. */
    static IValueType<?> variableCardOutputType(ItemStack stack) {
        IVariableFacade facade = variableFacade(stack);
        return facade != null && facade.isValid() ? facade.getOutputType() : null;
    }

    static int countBlankVariableCards(Player player) {
        if (player == null) {
            return 0;
        }
        int count = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (isBlankVariable(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    static int countEmptyPlayerSlots(Player player) {
        if (player == null) {
            return 0;
        }
        int count = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    static boolean isBlankVariable(ItemStack stack) {
        IVariableFacade facade = variableFacade(stack);
        return facade != null && !facade.isValid();
    }

    static boolean matchesExpectedType(IValueType<?> actualType, IValueType<?> expectedType) {
        return expectedType == null || (actualType != null && expectedType.correspondsTo(actualType));
    }

    private static IVariableFacade variableFacade(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemVariable variable)) {
            return null;
        }
        return variable.getVariableFacade(ValueDeseralizationContext.ofClient(), stack);
    }
}
