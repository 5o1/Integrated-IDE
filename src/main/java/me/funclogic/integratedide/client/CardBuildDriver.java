package me.funclogic.integratedide.client;

import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.Map;
import me.funclogic.integratedide.expr.ExpressionCompiler;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.cyclops.integrateddynamics.inventory.container.ContainerLogicProgrammerBase;
import org.slf4j.Logger;

/**
 * Advances one card build at a time. Menu packets and inventory mutation are
 * isolated behind {@link CardBuildPort}, so the production state machine can
 * be exercised through a complete, controlled workflow test.
 */
public final class CardBuildDriver {
    private static final int TICKS_BETWEEN_ACTIONS = 3;
    private static final int MAX_OUTPUT_WAIT_TICKS = 200;
    private static final Logger LOGGER = LogUtils.getLogger();

    private final CardBuildPort port;
    private final List<ExpressionCompiler.CardStep> steps;
    private int stepIndex;
    private int inputIndex;
    private int cooldown;
    private int outputWaitTicks;
    private Phase phase = Phase.IDLE;
    private String status = "\u7b49\u5f85\u5f00\u59cb";

    public CardBuildDriver(ContainerLogicProgrammerBase menu, List<ExpressionCompiler.CardStep> steps,
                           Map<String, ItemStack> existingCards) {
        this(new LogicProgrammerCardBuildPort(menu, existingCards), steps);
    }

    /**
     * Package-private injection point for FML-backed state-machine tests. The
     * public constructor always uses the real Logic Programmer menu port.
     */
    CardBuildDriver(CardBuildPort port, List<ExpressionCompiler.CardStep> steps) {
        this.port = port;
        this.steps = List.copyOf(steps);
    }

    public void start() {
        if (!port.isCurrent()) {
            fail("\u903b\u8f91\u7f16\u7a0b\u5668\u5df2\u5173\u95ed\u6216\u5207\u6362\uff0c\u672a\u5f00\u59cb\u751f\u6210");
            return;
        }
        phase = Phase.SELECT;
        status = "\u51c6\u5907\u751f\u6210 " + steps.size() + " \u5f20\u53d8\u91cf\u5361\u2026";
    }

    public boolean isRunning() {
        return phase != Phase.IDLE && phase != Phase.COMPLETE && phase != Phase.FAILED;
    }

    public boolean isFailed() {
        return phase == Phase.FAILED;
    }

    public boolean isComplete() {
        return phase == Phase.COMPLETE;
    }

    public Map<String, ItemStack> producedCards() {
        return port.producedCards();
    }

    public String status() {
        return status;
    }

    public void tick() {
        if (!isRunning()) {
            return;
        }
        if (!port.isCurrent()) {
            fail("\u903b\u8f91\u7f16\u7a0b\u5668\u5df2\u5173\u95ed\u6216\u5207\u6362\uff0c\u5df2\u505c\u6b62\u4ee5\u907f\u514d\u79fb\u52a8\u9519\u8bef\u7269\u54c1");
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        try {
            advance();
        } catch (RuntimeException error) {
            fail(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
    }

    private void advance() {
        if (stepIndex >= steps.size()) {
            phase = Phase.COMPLETE;
            status = "\u5b8c\u6210\uff1a\u5df2\u751f\u6210 " + steps.size()
                    + " \u5f20\u666e\u901a\u53d8\u91cf\u5361\u3002\u8bf7\u5c06\u4f9d\u8d56\u5361\u548c\u6700\u7ec8\u5361\u653e\u5165\u540c\u4e00 Variable Store\u3002";
            return;
        }
        ExpressionCompiler.CardStep step = steps.get(stepIndex);
        switch (phase) {
            case SELECT -> select(step);
            case CONFIGURE -> configure(step);
            case INSERT_INPUT -> insertInput(step);
            case PLACE_INPUT -> placeInput();
            case PICK_BLANK -> pickBlank();
            case PLACE_BLANK -> placeBlank();
            case RETURN_REMAINDER -> returnRemainder();
            case WAIT_FOR_OUTPUT -> waitForOutput();
            case STORE_OUTPUT -> storeOutput();
            case CONFIRM_OUTPUT -> confirmOutput(step);
            case CLEANUP_INPUT -> cleanupInput(step);
            default -> throw new IllegalStateException("Unknown card-build phase: " + phase);
        }
    }

    private void select(ExpressionCompiler.CardStep step) {
        port.select(step);
        inputIndex = 0;
        phase = step.kind() == ExpressionCompiler.StepKind.DYNAMIC_OPERATOR ? Phase.INSERT_INPUT : Phase.CONFIGURE;
        delay();
    }

    private void configure(ExpressionCompiler.CardStep step) {
        port.configure(step);
        phase = Phase.INSERT_INPUT;
        delay();
    }

    private void insertInput(ExpressionCompiler.CardStep step) {
        if (inputIndex >= step.inputs().size()) {
            phase = Phase.PICK_BLANK;
            return;
        }
        port.pickupInput(step.inputs().get(inputIndex));
        phase = Phase.PLACE_INPUT;
        delay();
    }

    private void placeInput() {
        port.placeInput(inputIndex);
        inputIndex++;
        phase = Phase.INSERT_INPUT;
        delay();
    }

    private void pickBlank() {
        port.pickupBlank();
        phase = Phase.PLACE_BLANK;
        delay();
    }

    private void placeBlank() {
        port.placeBlank();
        phase = Phase.RETURN_REMAINDER;
        delay();
    }

    private void returnRemainder() {
        port.returnBlankRemainder();
        phase = Phase.WAIT_FOR_OUTPUT;
        outputWaitTicks = 0;
        cooldown = 8;
    }

    private void waitForOutput() {
        if (!port.outputReady()) {
            outputWaitTicks += TICKS_BETWEEN_ACTIONS;
            if (outputWaitTicks > MAX_OUTPUT_WAIT_TICKS) {
                throw new IllegalStateException("等待第 " + (stepIndex + 1) + "/" + steps.size()
                        + " 张变量卡的输出超时；请检查逻辑编程器中的输入卡类型和背包空间");
            }
            status = "正在等待第 " + (stepIndex + 1) + "/" + steps.size() + " 张变量卡的输出…";
            cooldown = 2;
            return;
        }
        outputWaitTicks = 0;
        phase = Phase.STORE_OUTPUT;
    }

    private void storeOutput() {
        port.storeOutput();
        phase = Phase.CONFIRM_OUTPUT;
        delay();
    }

    private void confirmOutput(ExpressionCompiler.CardStep step) {
        port.confirmOutput(step.id());
        phase = Phase.CLEANUP_INPUT;
        inputIndex = 0;
    }

    private void cleanupInput(ExpressionCompiler.CardStep step) {
        if (inputIndex >= step.inputs().size()) {
            stepIndex++;
            phase = Phase.SELECT;
            status = "\u5df2\u751f\u6210 " + stepIndex + "/" + steps.size() + " \u5f20\u53d8\u91cf\u5361\u2026";
            return;
        }
        port.cleanupInput(inputIndex++);
        delay();
    }

    public static int countBlankVariableCards(Player player) {
        return CardInventory.countBlankVariableCards(player);
    }

    private void delay() {
        cooldown = TICKS_BETWEEN_ACTIONS;
    }

    private void fail(String message) {
        String step = steps.isEmpty() ? "none" : Integer.toString(Math.min(stepIndex + 1, steps.size()));
        LOGGER.warn("Integrated IDE stopped card build at step {}/{} in {}: {}", step, steps.size(), phase, message);
        phase = Phase.FAILED;
        status = "\u5df2\u505c\u6b62\uff1a" + message;
    }

    private enum Phase {
        IDLE, SELECT, CONFIGURE, INSERT_INPUT, PLACE_INPUT, PICK_BLANK, PLACE_BLANK, RETURN_REMAINDER,
        WAIT_FOR_OUTPUT, STORE_OUTPUT, CONFIRM_OUTPUT, CLEANUP_INPUT, COMPLETE, FAILED
    }
}
