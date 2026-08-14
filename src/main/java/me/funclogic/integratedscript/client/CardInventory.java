package me.funclogic.integratedscript.client;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.cyclops.integrateddynamics.api.evaluate.variable.ValueDeseralizationContext;
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

    static int findCompatiblePlayerSlotForCursor(ContainerLogicProgrammerBase menu, Player player) {
        ItemStack carried = menu.getCarried();
        for (int index = 0; index < menu.slots.size(); index++) {
            Slot slot = menu.slots.get(index);
            if (slot.container != player.getInventory()) {
                continue;
            }
            ItemStack candidate = slot.getItem();
            if (candidate.isEmpty() || ItemStack.isSameItemSameComponents(candidate, carried)) {
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

    static ItemStack blankVariableQueue(Player player, int count) {
        if (player == null || count <= 0) {
            return ItemStack.EMPTY;
        }
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (isBlankVariable(stack)) {
                ItemStack queue = stack.copy();
                queue.setCount(count);
                return queue;
            }
        }
        return ItemStack.EMPTY;
    }

    static boolean isBlankVariable(ItemStack stack) {
        if (!(stack.getItem() instanceof ItemVariable variable)) {
            return false;
        }
        return !variable.getVariableFacade(ValueDeseralizationContext.ofClient(), stack).isValid();
    }
}
