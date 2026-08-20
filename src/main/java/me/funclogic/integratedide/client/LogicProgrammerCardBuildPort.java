package me.funclogic.integratedide.client;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import me.funclogic.integratedide.expr.ExpressionCompiler;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.cyclops.integrateddynamics.inventory.container.ContainerLogicProgrammerBase;

/** Live client implementation of the card-build port. */
final class LogicProgrammerCardBuildPort implements CardBuildPort {
    private final LogicProgrammerGateway programmer;
    private final Map<String, ItemStack> produced = new LinkedHashMap<>();
    private final Map<Integer, Integer> placedInputIds = new HashMap<>();
    private int pendingInputId = -1;
    private int inputSourceSlot = -1;
    private int pendingOutputId = -1;
    private int blankSourceSlot = -1;
    private String errorBeforeAction;
    private int lastSynchronizedStateId;
    private long synchronizationRevision;

    LogicProgrammerCardBuildPort(ContainerLogicProgrammerBase menu, Map<String, ItemStack> existingCards) {
        this.programmer = new LogicProgrammerGateway(menu);
        this.produced.putAll(existingCards);
        this.lastSynchronizedStateId = menu.getStateId();
    }

    @Override
    public boolean isCurrent() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && minecraft.gameMode != null && programmer.isCurrentMenu(minecraft.player);
    }

    @Override
    public long synchronizationRevision() {
        int currentStateId = programmer.menu().getStateId();
        if (currentStateId != lastSynchronizedStateId) {
            lastSynchronizedStateId = currentStateId;
            synchronizationRevision++;
        }
        return synchronizationRevision;
    }

    @Override
    public String serverFailure() {
        if (errorBeforeAction == null) {
            return null;
        }
        String current = errorText();
        return current.isBlank() || current.equals(errorBeforeAction) ? null : current;
    }

    @Override
    public void select(ExpressionCompiler.CardStep step) {
        beforeServerAction();
        programmer.select(step);
    }

    @Override
    public void configure(ExpressionCompiler.CardStep step) {
        beforeServerAction();
        programmer.configure(step);
    }

    @Override
    public void pickupInput(String stepId) {
        Player player = player();
        ItemStack input = produced.get(stepId);
        if (input == null) {
            throw new IllegalStateException("\u7f3a\u5c11\u4e2d\u95f4\u53d8\u91cf\u5361 " + stepId);
        }
        int inputId = CardInventory.variableCardId(input);
        int sourceSlot = CardInventory.findVariableCardSlotById(programmer.menu(), player, inputId);
        if (sourceSlot < 0) {
            throw new IllegalStateException("\u627e\u4e0d\u5230\u4e2d\u95f4\u53d8\u91cf\u5361\uff1b\u751f\u6210\u671f\u95f4\u8bf7\u52ff\u79fb\u52a8\u80cc\u5305\u7269\u54c1");
        }
        pendingInputId = inputId;
        inputSourceSlot = sourceSlot;
        beforeServerAction();
        programmer.pickup(player, sourceSlot, 0);
    }

    @Override
    public boolean inputHeld(String stepId) {
        ItemStack expected = produced.get(stepId);
        return expected != null && CardInventory.variableCardId(programmer.carriedItem())
                == CardInventory.variableCardId(expected);
    }

    @Override
    public boolean inputSlotReady(int inputIndex) {
        return inputIndex >= 0 && inputIndex < LogicProgrammerMenuLayout.inputSlotCount(programmer.menu());
    }

    @Override
    public void placeInput(int inputIndex) {
        int target = LogicProgrammerMenuLayout.inputSlot(programmer.menu(), inputIndex);
        placedInputIds.put(inputIndex, pendingInputId);
        beforeServerAction();
        programmer.pickup(player(), target, 0);
    }

    @Override
    public boolean inputPlaced(int inputIndex, String stepId) {
        Integer expectedId = placedInputIds.get(inputIndex);
        if (expectedId == null || !inputSlotReady(inputIndex)) {
            return false;
        }
        int target = LogicProgrammerMenuLayout.inputSlot(programmer.menu(), inputIndex);
        return programmer.carriedItem().isEmpty()
                && CardInventory.variableCardId(programmer.slotItem(target)) == expectedId;
    }

    @Override
    public boolean returnHeldInput() {
        if (programmer.carriedItem().isEmpty()) {
            inputSourceSlot = -1;
            return false;
        }
        if (inputSourceSlot < 0) {
            throw new IllegalStateException("\u672a\u80fd\u5b9a\u4f4d\u8f93\u5165\u53d8\u91cf\u5361\u7684\u539f\u80cc\u5305\u683c\u3002");
        }
        beforeServerAction();
        programmer.pickup(player(), inputSourceSlot, 0);
        inputSourceSlot = -1;
        return true;
    }

    @Override
    public boolean inputCursorReturned() {
        return programmer.carriedItem().isEmpty();
    }

    @Override
    public void pickupBlank() {
        Player player = player();
        blankSourceSlot = CardInventory.findBlankVariableSlot(programmer.menu(), player);
        if (blankSourceSlot < 0) {
            throw new IllegalStateException("\u7a7a\u767d Variable Card \u5df2\u7528\u5c3d");
        }
        beforeServerAction();
        programmer.pickup(player, blankSourceSlot, 0);
    }

    @Override
    public boolean blankHeld() {
        return CardInventory.isBlankVariable(programmer.carriedItem());
    }

    @Override
    public void placeBlank() {
        beforeServerAction();
        programmer.pickup(player(), LogicProgrammerMenuLayout.writeSlot(programmer.menu()), 1);
    }

    @Override
    public boolean returnBlankRemainder() {
        if (programmer.carriedItem().isEmpty()) {
            blankSourceSlot = -1;
            return false;
        }
        if (blankSourceSlot < 0) {
            throw new IllegalStateException("\u80cc\u5305\u6ca1\u6709\u7a7a\u95f4\u653e\u56de\u53d8\u91cf\u5361\u961f\u5217");
        }
        beforeServerAction();
        programmer.pickup(player(), blankSourceSlot, 0);
        blankSourceSlot = -1;
        return true;
    }

    @Override
    public boolean blankRemainderReturned() {
        return programmer.carriedItem().isEmpty();
    }

    @Override
    public boolean outputReady() {
        ItemStack output = programmer.slotItem(LogicProgrammerMenuLayout.writeSlot(programmer.menu()));
        if (output.isEmpty() || CardInventory.isBlankVariable(output)) {
            return false;
        }
        int outputId = CardInventory.variableCardId(output);
        if (outputId < 0) {
            return false;
        }
        pendingOutputId = outputId;
        return true;
    }

    @Override
    public void returnOutput() {
        beforeServerAction();
        programmer.returnOutputToPlayer();
    }

    @Override
    public boolean outputReturned() {
        return pendingOutputId >= 0
                && programmer.slotIsEmpty(LogicProgrammerMenuLayout.writeSlot(programmer.menu()))
                && CardInventory.findVariableCardById(player(), pendingOutputId) != null;
    }

    @Override
    public void confirmOutput(String stepId) {
        ItemStack stored = CardInventory.findVariableCardById(player(), pendingOutputId);
        if (stored == null) {
            throw new IllegalStateException("\u7b49\u5f85\u5230\u4e86\u8f93\u51fa\u69fd\u66f4\u65b0\uff0c\u4f46\u7f3a\u5c11\u5df2\u540c\u6b65\u7684\u80cc\u5305\u8f93\u51fa");
        }
        produced.put(stepId, stored.copy());
        pendingOutputId = -1;
    }

    @Override
    public void cleanupInput(int inputIndex) {
        if (!inputSlotReady(inputIndex)) {
            throw new IllegalStateException("The active Logic Programmer element no longer exposes input "
                    + (inputIndex + 1) + ".");
        }
        int inputSlot = LogicProgrammerMenuLayout.inputSlot(programmer.menu(), inputIndex);
        if (!programmer.slotIsEmpty(inputSlot)) {
            beforeServerAction();
            programmer.quickMove(player(), inputSlot);
        }
    }

    @Override
    public boolean inputReturned(int inputIndex, String stepId) {
        Integer expectedId = placedInputIds.get(inputIndex);
        if (expectedId == null || !inputSlotReady(inputIndex)) {
            return false;
        }
        int inputSlot = LogicProgrammerMenuLayout.inputSlot(programmer.menu(), inputIndex);
        if (!programmer.slotIsEmpty(inputSlot)) {
            return false;
        }
        boolean returned = CardInventory.findVariableCardSlotById(programmer.menu(), player(), expectedId) >= 0;
        if (returned) {
            placedInputIds.remove(inputIndex);
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

    private void beforeServerAction() {
        errorBeforeAction = errorText();
    }

    private String errorText() {
        Component error = programmer.menu().getLastError();
        return error == null ? "" : error.getString();
    }
}
