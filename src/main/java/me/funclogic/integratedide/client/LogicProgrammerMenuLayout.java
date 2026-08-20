package me.funclogic.integratedide.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import org.cyclops.integrateddynamics.inventory.container.ContainerLogicProgrammerBase;

/**
 * Resolves the live Logic Programmer menu structure instead of assuming slot
 * numbers or texture coordinates. The programmer rebuilds its temporary input
 * slots whenever the selected element changes, so callers must resolve inputs
 * at the point of use.
 */
public final class LogicProgrammerMenuLayout {
    private LogicProgrammerMenuLayout() {
    }

    public static int writeSlot(ContainerLogicProgrammerBase menu) {
        return resolveWriteSlot(menu.slots, menu.getTemporaryInputSlots());
    }

    public static int inputSlot(ContainerLogicProgrammerBase menu, int inputIndex) {
        List<Integer> inputs = resolveInputSlots(menu.slots, menu.getTemporaryInputSlots());
        if (inputIndex < 0 || inputIndex >= inputs.size()) {
            throw new IllegalStateException("The active Logic Programmer element exposes " + inputs.size()
                    + " input slot(s), but input " + (inputIndex + 1) + " was requested.");
        }
        return inputs.get(inputIndex);
    }

    public static int inputSlotCount(ContainerLogicProgrammerBase menu) {
        return resolveInputSlots(menu.slots, menu.getTemporaryInputSlots()).size();
    }

    public static Slot writeSlotView(ContainerLogicProgrammerBase menu) {
        return menu.slots.get(writeSlot(menu));
    }

    static int resolveWriteSlot(List<Slot> slots, Container temporaryInputs) {
        List<Integer> candidates = new ArrayList<>();
        for (int index = 0; index < slots.size(); index++) {
            Slot slot = slots.get(index);
            if (slot.container != temporaryInputs && slot.container.getContainerSize() == 1) {
                candidates.add(index);
            }
        }
        if (candidates.size() != 1) {
            throw new IllegalStateException("Unsupported Logic Programmer menu: expected exactly one persistent "
                    + "single-card write slot, found " + candidates.size() + ".");
        }
        return candidates.getFirst();
    }

    static List<Integer> resolveInputSlots(List<Slot> slots, Container temporaryInputs) {
        if (temporaryInputs == null) {
            return List.of();
        }
        List<IndexedSlot> inputs = new ArrayList<>();
        for (int index = 0; index < slots.size(); index++) {
            Slot slot = slots.get(index);
            if (slot.container == temporaryInputs) {
                inputs.add(new IndexedSlot(index, slot.getContainerSlot()));
            }
        }
        inputs.sort(Comparator.comparingInt(IndexedSlot::containerIndex));
        if (inputs.size() != temporaryInputs.getContainerSize()) {
            throw new IllegalStateException("Unsupported Logic Programmer menu: expected "
                    + temporaryInputs.getContainerSize() + " temporary input slot(s), found " + inputs.size() + ".");
        }
        for (int expected = 0; expected < inputs.size(); expected++) {
            if (inputs.get(expected).containerIndex() != expected) {
                throw new IllegalStateException("Unsupported Logic Programmer menu: temporary input slots are not "
                        + "indexed consecutively from zero.");
            }
        }
        return inputs.stream().map(IndexedSlot::menuIndex).toList();
    }

    private record IndexedSlot(int menuIndex, int containerIndex) {
    }
}
