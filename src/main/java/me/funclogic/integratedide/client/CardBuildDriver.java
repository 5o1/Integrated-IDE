package me.funclogic.integratedide.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.funclogic.integratedide.expr.ExpressionCompiler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.cyclops.integrateddynamics.inventory.container.ContainerLogicProgrammerBase;

/**
 * A small state machine that turns a dependency plan into ordinary Variable
 * Cards. GUI packets, literal conversion, and inventory lookup live in their
 * dedicated collaborators so this class only owns build progress.
 */
public final class CardBuildDriver {
    private static final int WRITE_SLOT = 0;
    private static final int FIRST_INPUT_SLOT = 4;
    private static final int TICKS_BETWEEN_ACTIONS = 3;

    private final LogicProgrammerGateway programmer;
    private final List<ExpressionCompiler.CardStep> steps;
    private final Map<String, ItemStack> produced = new HashMap<>();
    private int stepIndex;
    private int inputIndex;
    private int cooldown;
    private Phase phase = Phase.IDLE;
    private ItemStack pendingOutput = ItemStack.EMPTY;
    private String status = "等待开始";

    public CardBuildDriver(ContainerLogicProgrammerBase menu, ExpressionCompiler.Compilation compilation) {
        this.programmer = new LogicProgrammerGateway(menu);
        this.steps = compilation.steps();
    }

    public void start() {
        if (Minecraft.getInstance().player == null) {
            fail("未找到玩家实例");
            return;
        }
        phase = Phase.SELECT;
        status = "准备生成 " + steps.size() + " 张变量卡…";
    }

    public boolean isRunning() {
        return phase != Phase.IDLE && phase != Phase.COMPLETE && phase != Phase.FAILED;
    }

    public boolean isFailed() {
        return phase == Phase.FAILED;
    }

    public String status() {
        return status;
    }

    public void tick() {
        if (!isRunning()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.gameMode == null || !programmer.isCurrentMenu(minecraft.player)) {
            fail("逻辑编程器已关闭或切换，已停止以避免移动错误物品");
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        try {
            advance(minecraft.player);
        } catch (RuntimeException error) {
            fail(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
    }

    private void advance(Player player) {
        if (stepIndex >= steps.size()) {
            phase = Phase.COMPLETE;
            status = "完成：已生成 " + steps.size() + " 张普通变量卡。请将依赖卡和最终卡放入同一 Variable Store。";
            return;
        }
        ExpressionCompiler.CardStep step = steps.get(stepIndex);
        switch (phase) {
            case SELECT -> select(step);
            case CONFIGURE -> configure(step);
            case INSERT_INPUT -> insertInput(player, step);
            case PLACE_INPUT -> placeInput(player);
            case PICK_BLANK -> pickBlank(player);
            case PLACE_BLANK -> placeBlank(player);
            case RETURN_REMAINDER -> returnRemainder(player);
            case WAIT_FOR_OUTPUT -> waitForOutput();
            case STORE_OUTPUT -> storeOutput(player);
            case CONFIRM_OUTPUT -> confirmOutput(player, step);
            case CLEANUP_INPUT -> cleanupInput(player, step);
            default -> throw new IllegalStateException("未知生成状态 " + phase);
        }
    }

    private void select(ExpressionCompiler.CardStep step) {
        programmer.select(step);
        inputIndex = 0;
        phase = step.kind() == ExpressionCompiler.StepKind.DYNAMIC_OPERATOR ? Phase.INSERT_INPUT : Phase.CONFIGURE;
        delay();
    }

    private void configure(ExpressionCompiler.CardStep step) {
        programmer.configure(step);
        phase = Phase.INSERT_INPUT;
        delay();
    }

    private void insertInput(Player player, ExpressionCompiler.CardStep step) {
        if (inputIndex >= step.inputs().size()) {
            phase = Phase.PICK_BLANK;
            return;
        }
        ItemStack input = produced.get(step.inputs().get(inputIndex));
        if (input == null) {
            throw new IllegalStateException("缺少中间变量卡 " + step.inputs().get(inputIndex));
        }
        int sourceSlot = CardInventory.findPlayerSlot(programmer.menu(), player, input);
        if (sourceSlot < 0) {
            throw new IllegalStateException("找不到中间变量卡；生成期间请勿移动背包物品");
        }
        programmer.pickup(player, sourceSlot, 0);
        phase = Phase.PLACE_INPUT;
        delay();
    }

    private void placeInput(Player player) {
        int target = FIRST_INPUT_SLOT + inputIndex;
        if (target >= programmer.slotCount()) {
            throw new IllegalStateException("原版逻辑编程器尚未创建输入槽");
        }
        programmer.pickup(player, target, 0);
        inputIndex++;
        phase = Phase.INSERT_INPUT;
        delay();
    }

    private void pickBlank(Player player) {
        int sourceSlot = CardInventory.findBlankVariableSlot(programmer.menu(), player);
        if (sourceSlot < 0) {
            throw new IllegalStateException("空白 Variable Card 已用尽");
        }
        programmer.pickup(player, sourceSlot, 0);
        phase = Phase.PLACE_BLANK;
        delay();
    }

    private void placeBlank(Player player) {
        programmer.pickup(player, WRITE_SLOT, 1);
        phase = Phase.RETURN_REMAINDER;
        delay();
    }

    private void returnRemainder(Player player) {
        int sourceSlot = CardInventory.findCompatiblePlayerSlotForCursor(programmer.menu(), player);
        if (sourceSlot < 0) {
            throw new IllegalStateException("背包没有空间放回变量卡队列");
        }
        programmer.pickup(player, sourceSlot, 0);
        phase = Phase.WAIT_FOR_OUTPUT;
        cooldown = 8;
    }

    private void waitForOutput() {
        ItemStack output = programmer.slotItem(WRITE_SLOT);
        if (output.isEmpty() || CardInventory.isBlankVariable(output)) {
            cooldown = 2;
            return;
        }
        pendingOutput = output.copy();
        phase = Phase.STORE_OUTPUT;
    }

    private void storeOutput(Player player) {
        programmer.quickMove(player, WRITE_SLOT);
        phase = Phase.CONFIRM_OUTPUT;
        delay();
    }

    private void confirmOutput(Player player, ExpressionCompiler.CardStep step) {
        if (!programmer.slotIsEmpty(WRITE_SLOT)) {
            throw new IllegalStateException("背包没有空位保存新变量卡");
        }
        ItemStack stored = CardInventory.findMatchingPlayerStack(programmer.menu(), player, pendingOutput);
        if (stored == null) {
            throw new IllegalStateException("未能在背包中定位新变量卡");
        }
        produced.put(step.id(), stored.copy());
        phase = Phase.CLEANUP_INPUT;
        inputIndex = 0;
    }

    private void cleanupInput(Player player, ExpressionCompiler.CardStep step) {
        if (inputIndex >= step.inputs().size()) {
            stepIndex++;
            phase = Phase.SELECT;
            status = "已生成 " + stepIndex + "/" + steps.size() + " 张变量卡…";
            return;
        }
        int inputSlot = FIRST_INPUT_SLOT + inputIndex++;
        if (inputSlot < programmer.slotCount() && !programmer.slotIsEmpty(inputSlot)) {
            programmer.quickMove(player, inputSlot);
            delay();
        }
    }

    public static int countBlankVariableCards(Player player) {
        return CardInventory.countBlankVariableCards(player);
    }

    public static ItemStack blankVariableQueue(Player player, int count) {
        return CardInventory.blankVariableQueue(player, count);
    }

    private void delay() {
        cooldown = TICKS_BETWEEN_ACTIONS;
    }

    private void fail(String message) {
        phase = Phase.FAILED;
        status = "已停止：" + message;
    }

    private enum Phase {
        IDLE, SELECT, CONFIGURE, INSERT_INPUT, PLACE_INPUT, PICK_BLANK, PLACE_BLANK, RETURN_REMAINDER,
        WAIT_FOR_OUTPUT, STORE_OUTPUT, CONFIRM_OUTPUT, CLEANUP_INPUT, COMPLETE, FAILED
    }
}
