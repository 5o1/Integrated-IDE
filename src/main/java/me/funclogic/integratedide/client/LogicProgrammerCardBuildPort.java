package me.funclogic.integratedide.client;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import me.funclogic.integratedide.expr.ExpressionCompiler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.cyclops.integrateddynamics.inventory.container.ContainerLogicProgrammerBase;

/** Live client implementation of the card-build port. */
final class LogicProgrammerCardBuildPort implements CardBuildPort {
    private static final int WRITE_SLOT = 0;
    private static final int FIRST_INPUT_SLOT = 4;

    private final LogicProgrammerGateway programmer;
    private final Map<String, ItemStack> produced = new LinkedHashMap<>();
    private final Map<Integer, ItemStack> placedInputs = new HashMap<>();
    private ItemStack pendingInput = ItemStack.EMPTY;
    private ItemStack pendingOutput = ItemStack.EMPTY;
    private int blankSourceSlot = -1;

    LogicProgrammerCardBuildPort(ContainerLogicProgrammerBase menu, Map<String, ItemStack> existingCards) {
        this.programmer = new LogicProgrammerGateway(menu);
        this.produced.putAll(existingCards);
    }

    @Override
    public boolean isCurrent() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && minecraft.gameMode != null && programmer.isCurrentMenu(minecraft.player);
    }

    @Override
    public void select(ExpressionCompiler.CardStep step) {
        programmer.select(step);
    }

    @Override
    public void configure(ExpressionCompiler.CardStep step) {
        programmer.configure(step);
    }

    @Override
    public void pickupInput(String stepId) {
        Player player = player();
        ItemStack input = produced.get(stepId);
        if (input == null) {
            throw new IllegalStateException("\u7f3a\u5c11\u4e2d\u95f4\u53d8\u91cf\u5361 " + stepId);
        }
        int sourceSlot = CardInventory.findPlayerSlot(programmer.menu(), player, input);
        if (sourceSlot < 0) {
            throw new IllegalStateException("\u627e\u4e0d\u5230\u4e2d\u95f4\u53d8\u91cf\u5361\uff1b\u751f\u6210\u671f\u95f4\u8bf7\u52ff\u79fb\u52a8\u80cc\u5305\u7269\u54c1");
        }
        pendingInput = input.copy();
        programmer.pickup(player, sourceSlot, 0);
    }

    @Override
    public boolean inputHeld(String stepId) {
        ItemStack expected = produced.get(stepId);
        return expected != null && ItemStack.isSameItemSameComponents(programmer.carriedItem(), expected);
    }

    @Override
    public boolean inputSlotReady(int inputIndex) {
        return FIRST_INPUT_SLOT + inputIndex < programmer.slotCount();
    }

    @Override
    public void placeInput(int inputIndex) {
        int target = FIRST_INPUT_SLOT + inputIndex;
        if (!inputSlotReady(inputIndex)) {
            throw new IllegalStateException("\u539f\u7248\u903b\u8f91\u7f16\u7a0b\u5668\u5c1a\u672a\u521b\u5efa\u8f93\u5165\u69fd");
        }
        placedInputs.put(inputIndex, pendingInput.copy());
        programmer.pickup(player(), target, 0);
    }

    @Override
    public boolean inputPlaced(int inputIndex, String stepId) {
        ItemStack expected = placedInputs.get(inputIndex);
        int target = FIRST_INPUT_SLOT + inputIndex;
        return expected != null && inputSlotReady(inputIndex) && programmer.carriedItem().isEmpty()
                && ItemStack.isSameItemSameComponents(programmer.slotItem(target), expected);
    }

    @Override
    public void pickupBlank() {
        Player player = player();
        blankSourceSlot = CardInventory.findBlankVariableSlot(programmer.menu(), player);
        if (blankSourceSlot < 0) {
            throw new IllegalStateException("\u7a7a\u767d Variable Card \u5df2\u7528\u5c3d");
        }
        programmer.pickup(player, blankSourceSlot, 0);
    }

    @Override
    public boolean blankHeld() {
        return CardInventory.isBlankVariable(programmer.carriedItem());
    }

    @Override
    public void placeBlank() {
        programmer.pickup(player(), WRITE_SLOT, 1);
    }

    @Override
    public void returnBlankRemainder() {
        if (blankSourceSlot < 0) {
            throw new IllegalStateException("\u80cc\u5305\u6ca1\u6709\u7a7a\u95f4\u653e\u56de\u53d8\u91cf\u5361\u961f\u5217");
        }
        programmer.pickup(player(), blankSourceSlot, 0);
        blankSourceSlot = -1;
    }

    @Override
    public boolean blankRemainderReturned() {
        return programmer.carriedItem().isEmpty();
    }

    @Override
    public boolean outputReady() {
        ItemStack output = programmer.slotItem(WRITE_SLOT);
        if (output.isEmpty() || CardInventory.isBlankVariable(output)) {
            return false;
        }
        pendingOutput = output.copy();
        return true;
    }

    @Override
    public void storeOutput() {
        programmer.quickMove(player(), WRITE_SLOT);
    }

    @Override
    public boolean outputStored() {
        return pendingOutput != null && !pendingOutput.isEmpty() && programmer.slotIsEmpty(WRITE_SLOT)
                && CardInventory.findMatchingPlayerStack(programmer.menu(), player(), pendingOutput) != null;
    }

    @Override
    public void confirmOutput(String stepId) {
        ItemStack stored = CardInventory.findMatchingPlayerStack(programmer.menu(), player(), pendingOutput);
        if (stored == null) {
            throw new IllegalStateException("\u7b49\u5f85\u5230\u4e86\u8f93\u51fa\u69fd\u66f4\u65b0\uff0c\u4f46\u7f3a\u5c11\u5df2\u540c\u6b65\u7684\u80cc\u5305\u8f93\u51fa");
        }
        produced.put(stepId, stored.copy());
        pendingOutput = ItemStack.EMPTY;
    }

    @Override
    public void cleanupInput(int inputIndex) {
        int inputSlot = FIRST_INPUT_SLOT + inputIndex;
        if (inputSlot < programmer.slotCount() && !programmer.slotIsEmpty(inputSlot)) {
            programmer.quickMove(player(), inputSlot);
        }
    }

    @Override
    public boolean inputReturned(int inputIndex, String stepId) {
        int inputSlot = FIRST_INPUT_SLOT + inputIndex;
        ItemStack expected = placedInputs.get(inputIndex);
        if (expected == null || inputSlot >= programmer.slotCount() || !programmer.slotIsEmpty(inputSlot)) {
            return false;
        }
        boolean returned = CardInventory.findPlayerSlot(programmer.menu(), player(), expected) >= 0;
        if (returned) {
            placedInputs.remove(inputIndex);
        }
        return returned;
    }

    @Override
    public Map<String, ItemStack> producedCards() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(produced));
    }

    private Player player() {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            throw new IllegalStateException("\u672a\u627e\u5230\u73a9\u5bb6\u5b9e\u4f8b");
        }
        return player;
    }
}
