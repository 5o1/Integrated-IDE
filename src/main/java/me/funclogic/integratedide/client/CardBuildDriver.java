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
 * Drives one Variable Card plan through the normal Logic Programmer menu.
 * Commands are never separated by guessed tick delays: every menu mutation
 * that requires a server response waits for its corresponding synchronized
 * container or inventory state before the next command is sent.
 */
public final class CardBuildDriver {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final CardBuildPort port;
    private final List<ExpressionCompiler.CardStep> steps;
    private int stepIndex;
    private int inputIndex;
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

    /**
     * Called from the client tick event. It dispatches consecutive local
     * commands immediately, then stops only at a concrete synchronization
     * condition. Every mutating command forms a state barrier, so a valid
     * plan cannot spin through unbounded transitions in one client tick.
     */
    public void tick() {
        if (!isRunning()) {
            return;
        }
        if (!port.isCurrent()) {
            fail("\u903b\u8f91\u7f16\u7a0b\u5668\u5df2\u5173\u95ed\u6216\u5207\u6362\uff0c\u5df2\u505c\u6b62\u4ee5\u907f\u514d\u79fb\u52a8\u9519\u8bef\u7269\u54c1");
            return;
        }
        try {
            while (isRunning() && advance()) {
                // Continue only through local state transitions. Each menu
                // mutation returns false and waits for a synchronized result.
            }
        } catch (RuntimeException error) {
            fail(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
    }

    /** @return whether another command can be dispatched in the same client tick. */
    private boolean advance() {
        if (stepIndex >= steps.size()) {
            phase = Phase.COMPLETE;
            status = "\u5b8c\u6210\uff1a\u5df2\u751f\u6210 " + steps.size()
                    + " \u5f20\u666e\u901a\u53d8\u91cf\u5361\u3002\u8bf7\u5c06\u4f9d\u8d56\u5361\u548c\u6700\u7ec8\u5361\u653e\u5165\u540c\u4e00 Variable Store\u3002";
            return false;
        }
        ExpressionCompiler.CardStep step = steps.get(stepIndex);
        return switch (phase) {
            case SELECT -> select(step);
            case CONFIGURE -> configure(step);
            case WAIT_INPUT_SLOT -> waitForInputSlot(step);
            case PICK_INPUT -> pickupInput(step);
            case WAIT_INPUT_HELD -> waitForInputHeld(step);
            case PLACE_INPUT -> placeInput();
            case WAIT_INPUT_PLACED -> waitForInputPlaced(step);
            case PICK_BLANK -> pickupBlank();
            case WAIT_BLANK_HELD -> waitForBlankHeld();
            case PLACE_BLANK -> placeBlank();
            case WAIT_OUTPUT_READY -> waitForOutputReady();
            case RETURN_REMAINDER -> returnRemainder();
            case WAIT_REMAINDER_RETURNED -> waitForRemainderReturned();
            case STORE_OUTPUT -> storeOutput();
            case WAIT_OUTPUT_STORED -> waitForOutputStored();
            case CONFIRM_OUTPUT -> confirmOutput(step);
            case CLEANUP_INPUT -> cleanupInput(step);
            case WAIT_INPUT_RETURNED -> waitForInputReturned(step);
            default -> throw new IllegalStateException("Unknown card-build phase: " + phase);
        };
    }

    private boolean select(ExpressionCompiler.CardStep step) {
        port.select(step);
        inputIndex = 0;
        if (step.kind() != ExpressionCompiler.StepKind.DYNAMIC_OPERATOR) {
            phase = Phase.CONFIGURE;
        } else if (step.inputs().isEmpty()) {
            phase = Phase.PICK_BLANK;
        } else {
            phase = Phase.WAIT_INPUT_SLOT;
        }
        return true;
    }

    private boolean configure(ExpressionCompiler.CardStep step) {
        port.configure(step);
        phase = Phase.PICK_BLANK;
        return true;
    }

    private boolean waitForInputSlot(ExpressionCompiler.CardStep step) {
        if (!port.inputSlotReady(inputIndex)) {
            return waiting("\u6b63\u5728\u7b49\u5f85\u670d\u52a1\u5668\u521b\u5efa\u7b2c " + (inputIndex + 1)
                    + " \u4e2a\u8f93\u5165\u69fd\u2026");
        }
        phase = Phase.PICK_INPUT;
        return true;
    }

    private boolean pickupInput(ExpressionCompiler.CardStep step) {
        port.pickupInput(step.inputs().get(inputIndex));
        phase = Phase.WAIT_INPUT_HELD;
        return false;
    }

    private boolean waitForInputHeld(ExpressionCompiler.CardStep step) {
        String input = step.inputs().get(inputIndex);
        if (!port.inputHeld(input)) {
            return waiting("\u6b63\u5728\u7b49\u5f85\u670d\u52a1\u5668\u540c\u6b65\u7b2c " + (inputIndex + 1)
                    + " \u5f20\u8f93\u5165\u53d8\u91cf\u5361\u2026");
        }
        phase = Phase.PLACE_INPUT;
        return true;
    }

    private boolean placeInput() {
        port.placeInput(inputIndex);
        phase = Phase.WAIT_INPUT_PLACED;
        return false;
    }

    private boolean waitForInputPlaced(ExpressionCompiler.CardStep step) {
        String input = step.inputs().get(inputIndex);
        if (!port.inputPlaced(inputIndex, input)) {
            return waiting("\u6b63\u5728\u7b49\u5f85\u670d\u52a1\u5668\u786e\u8ba4\u7b2c " + (inputIndex + 1)
                    + " \u5f20\u8f93\u5165\u53d8\u91cf\u5361\u2026");
        }
        inputIndex++;
        phase = inputIndex < step.inputs().size() ? Phase.WAIT_INPUT_SLOT : Phase.PICK_BLANK;
        return true;
    }

    private boolean pickupBlank() {
        port.pickupBlank();
        phase = Phase.WAIT_BLANK_HELD;
        return false;
    }

    private boolean waitForBlankHeld() {
        if (!port.blankHeld()) {
            return waiting("\u6b63\u5728\u7b49\u5f85\u670d\u52a1\u5668\u540c\u6b65\u7a7a\u767d Variable Card\u2026");
        }
        phase = Phase.PLACE_BLANK;
        return true;
    }

    private boolean placeBlank() {
        port.placeBlank();
        phase = Phase.WAIT_OUTPUT_READY;
        return false;
    }

    private boolean waitForOutputReady() {
        if (!port.outputReady()) {
            return waiting("\u6b63\u5728\u7b49\u5f85\u670d\u52a1\u5668\u5199\u5165\u53d8\u91cf\u5361\u8f93\u51fa\u2026");
        }
        phase = Phase.RETURN_REMAINDER;
        return true;
    }

    private boolean returnRemainder() {
        port.returnBlankRemainder();
        phase = Phase.WAIT_REMAINDER_RETURNED;
        return false;
    }

    private boolean waitForRemainderReturned() {
        if (!port.blankRemainderReturned()) {
            return waiting("\u6b63\u5728\u7b49\u5f85\u670d\u52a1\u5668\u8fd4\u56de\u7a7a\u767d Variable Card \u4f59\u91cf\u2026");
        }
        phase = Phase.STORE_OUTPUT;
        return true;
    }

    private boolean storeOutput() {
        port.storeOutput();
        phase = Phase.WAIT_OUTPUT_STORED;
        return false;
    }

    private boolean waitForOutputStored() {
        if (!port.outputStored()) {
            return waiting("\u6b63\u5728\u7b49\u5f85\u670d\u52a1\u5668\u540c\u6b65\u65b0\u53d8\u91cf\u5361\u5230\u80cc\u5305\u2026");
        }
        phase = Phase.CONFIRM_OUTPUT;
        return true;
    }

    private boolean confirmOutput(ExpressionCompiler.CardStep step) {
        port.confirmOutput(step.id());
        inputIndex = 0;
        phase = Phase.CLEANUP_INPUT;
        return true;
    }

    private boolean cleanupInput(ExpressionCompiler.CardStep step) {
        if (inputIndex >= step.inputs().size()) {
            stepIndex++;
            phase = Phase.SELECT;
            status = "\u5df2\u751f\u6210 " + stepIndex + "/" + steps.size() + " \u5f20\u53d8\u91cf\u5361\u2026";
            return true;
        }
        port.cleanupInput(inputIndex);
        phase = Phase.WAIT_INPUT_RETURNED;
        return false;
    }

    private boolean waitForInputReturned(ExpressionCompiler.CardStep step) {
        String input = step.inputs().get(inputIndex);
        if (!port.inputReturned(inputIndex, input)) {
            return waiting("\u6b63\u5728\u7b49\u5f85\u670d\u52a1\u5668\u5f52\u8fd8\u8f93\u5165\u53d8\u91cf\u5361\u2026");
        }
        inputIndex++;
        phase = Phase.CLEANUP_INPUT;
        return true;
    }

    private boolean waiting(String message) {
        status = message;
        return false;
    }

    public static int countBlankVariableCards(Player player) {
        return CardInventory.countBlankVariableCards(player);
    }

    private void fail(String message) {
        String step = steps.isEmpty() ? "none" : Integer.toString(Math.min(stepIndex + 1, steps.size()));
        LOGGER.warn("Integrated IDE stopped card build at step {}/{} in {}: {}", step, steps.size(), phase, message);
        phase = Phase.FAILED;
        status = "\u5df2\u505c\u6b62\uff1a" + message;
    }

    private enum Phase {
        IDLE, SELECT, CONFIGURE, WAIT_INPUT_SLOT, PICK_INPUT, WAIT_INPUT_HELD, PLACE_INPUT,
        WAIT_INPUT_PLACED, PICK_BLANK, WAIT_BLANK_HELD, PLACE_BLANK, WAIT_OUTPUT_READY,
        RETURN_REMAINDER, WAIT_REMAINDER_RETURNED, STORE_OUTPUT, WAIT_OUTPUT_STORED, CONFIRM_OUTPUT,
        CLEANUP_INPUT, WAIT_INPUT_RETURNED, COMPLETE, FAILED
    }
}
