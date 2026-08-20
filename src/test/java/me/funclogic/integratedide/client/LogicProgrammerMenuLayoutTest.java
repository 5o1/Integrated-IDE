package me.funclogic.integratedide.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import org.junit.jupiter.api.Test;

class LogicProgrammerMenuLayoutTest {
    @Test
    void resolvesWriteAndTemporarySlotsFromTheirLiveContainers() {
        SimpleContainer write = new SimpleContainer(1);
        SimpleContainer filters = new SimpleContainer(3);
        SimpleContainer inputs = new SimpleContainer(2);
        SimpleContainer player = new SimpleContainer(36);
        List<Slot> slots = List.of(
                new Slot(write, 0, 232, 110),
                new Slot(filters, 0, 6, 218),
                new Slot(filters, 1, 24, 218),
                new Slot(filters, 2, 58, 218),
                new Slot(inputs, 1, 150, 50),
                new Slot(player, 0, 8, 140),
                new Slot(inputs, 0, 120, 50));

        assertEquals(0, LogicProgrammerMenuLayout.resolveWriteSlot(slots, inputs));
        assertEquals(List.of(6, 4), LogicProgrammerMenuLayout.resolveInputSlots(slots, inputs));
    }

    @Test
    void rejectsAnAmbiguousMenuInsteadOfClickingAnAssumedSlot() {
        SimpleContainer firstWrite = new SimpleContainer(1);
        SimpleContainer secondWrite = new SimpleContainer(1);
        SimpleContainer inputs = new SimpleContainer(0);

        assertThrows(IllegalStateException.class, () -> LogicProgrammerMenuLayout.resolveWriteSlot(List.of(
                new Slot(firstWrite, 0, 0, 0), new Slot(secondWrite, 0, 18, 0)), inputs));
    }
}
