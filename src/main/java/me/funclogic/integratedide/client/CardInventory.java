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

    static int findPlayerSlot(ContainerLogicProgrammerBase menu, Player player, ItemStack expected) {
        for (int index = 0; index < menu.slots.size(); index++) {
            Slot slot = menu.slots.get(index);
            if (slot.container == player.getInventory() && ItemStack.isSameItemSameComponents(slot.getItem(), expected)) {
                return index;
            }
        }
        return -1;
    }

    static ItemStack findMatchingPlayerStack(ContainerLogicProgrammerBase menu, Player player, ItemStack expected) {
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory() && ItemStack.isSameItemSameComponents(slot.getItem(), expected)) {
                return slot.getItem();
            }
        }
        return null;
    }

    static ItemStack findVariableCardById(Player player, int variableCardId, String expectedTypeId) {
        if (player == null) {
            return null;
        }
        Identifier expectedId = Identifier.tryParse(expectedTypeId);
        IValueType<?> expectedType = expectedId == null ? null : ValueTypes.REGISTRY.getValueType(expectedId);
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
