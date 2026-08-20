package me.funclogic.integratedide.client;

import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.Map;
import me.funclogic.integratedide.expr.ExpressionCompiler;
import net.minecraft.network.chat.Component;
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
    /** The server snapshot revision that was current before the last menu mutation. */
    private long commandRevision;
    private Phase phase = Phase.IDLE;
    private Component status = Component.translatable("integratedide.build.waiting");

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
            fail(Component.translatable("integratedide.build.not_started"));
            return;
        }
        phase = Phase.SELECT;
        status = Component.translatable("integratedide.build.preparing", steps.size());
    }

    public boolean isRunning() {
        return phase != Phase.IDLE && phase != Phase.COMPLETE && phase != Phase.CANCELLED && phase != Phase.FAILED;
    }

    public boolean isFailed() {
        return phase == Phase.FAILED;
    }

    public boolean isComplete() {
        return phase == Phase.COMPLETE;
    }

    /**
     * Stops automation without sending any more inventory clicks. Temporary
     * cards stay in the vanilla programmer so the player can recover them
     * visibly instead of an opaque client-side rollback guessing their state.
     */
    public void cancel() {
        if (!isRunning()) {
            return;
        }
        phase = Phase.CANCELLED;
        status = Component.translatable("integratedide.build.cancelled");
    }

    public Map<String, ItemStack> producedCards() {
        return port.producedCards();
    }

    public String status() {
        return status.getString();
    }

    Component statusComponent() {
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
            fail(Component.translatable("integratedide.build.menu_changed"));
            return;
        }
        String serverFailure = port.serverFailure();
        if (serverFailure != null) {
            fail(Component.translatable("integratedide.build.server_rejected", serverFailure));
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
            status = Component.translatable("integratedide.build.complete", steps.size());
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
            case CLEANUP_INPUT -> cleanupInput(step);
            case WAIT_INPUT_RETURNED -> waitForInputReturned(step);
            case RETURN_OUTPUT -> returnOutput();
            case WAIT_OUTPUT_RETURNED -> waitForOutputReturned();
            case CONFIRM_OUTPUT -> confirmOutput(step);
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
            return waiting(Component.translatable("integratedide.build.wait.input_slot", inputIndex + 1));
        }
        phase = Phase.PICK_INPUT;
        return true;
    }

    private boolean pickupInput(ExpressionCompiler.CardStep step) {
        issue(() -> port.pickupInput(step.inputs().get(inputIndex)));
        phase = Phase.WAIT_INPUT_HELD;
        return false;
    }

    private boolean waitForInputHeld(ExpressionCompiler.CardStep step) {
        String input = step.inputs().get(inputIndex);
        if (!synchronizedAfterCommand() || !port.inputHeld(input)) {
            return waiting(Component.translatable("integratedide.build.wait.input_held", inputIndex + 1));
        }
        phase = Phase.PLACE_INPUT;
        return true;
    }

    private boolean placeInput() {
        issue(() -> port.placeInput(inputIndex));
        phase = Phase.WAIT_INPUT_PLACED;
        return false;
    }

    private boolean waitForInputPlaced(ExpressionCompiler.CardStep step) {
        String input = step.inputs().get(inputIndex);
        if (!synchronizedAfterCommand() || !port.inputPlaced(inputIndex, input)) {
            return waiting(Component.translatable("integratedide.build.wait.input_placed", inputIndex + 1));
        }
        inputIndex++;
        phase = inputIndex < step.inputs().size() ? Phase.WAIT_INPUT_SLOT : Phase.PICK_BLANK;
        return true;
    }

    private boolean pickupBlank() {
        issue(port::pickupBlank);
        phase = Phase.WAIT_BLANK_HELD;
        return false;
    }

    private boolean waitForBlankHeld() {
        if (!synchronizedAfterCommand() || !port.blankHeld()) {
            return waiting(Component.translatable("integratedide.build.wait.blank_held"));
        }
        phase = Phase.PLACE_BLANK;
        return true;
    }

    private boolean placeBlank() {
        issue(port::placeBlank);
        phase = Phase.WAIT_OUTPUT_READY;
        return false;
    }

    private boolean waitForOutputReady() {
        if (!synchronizedAfterCommand() || !port.outputReady()) {
            return waiting(Component.translatable("integratedide.build.wait.output_ready"));
        }
        phase = Phase.RETURN_REMAINDER;
        return true;
    }

    private boolean returnRemainder() {
        commandRevision = port.synchronizationRevision();
        if (!port.returnBlankRemainder()) {
            inputIndex = 0;
            phase = Phase.CLEANUP_INPUT;
            return true;
        }
        phase = Phase.WAIT_REMAINDER_RETURNED;
        return false;
    }

    private boolean waitForRemainderReturned() {
        if (!synchronizedAfterCommand() || !port.blankRemainderReturned()) {
            return waiting(Component.translatable("integratedide.build.wait.remainder"));
        }
        // The dedicated reset packet clears the active element, so all
        // temporary operator inputs must be returned before it is sent.
        inputIndex = 0;
        phase = Phase.CLEANUP_INPUT;
        return true;
    }

    private boolean returnOutput() {
        issue(port::returnOutput);
        phase = Phase.WAIT_OUTPUT_RETURNED;
        return false;
    }

    private boolean waitForOutputReturned() {
        if (!synchronizedAfterCommand() || !port.outputReturned()) {
            return waiting(Component.translatable("integratedide.build.wait.output_stored"));
        }
        phase = Phase.CONFIRM_OUTPUT;
        return true;
    }

    private boolean confirmOutput(ExpressionCompiler.CardStep step) {
        port.confirmOutput(step.id());
        stepIndex++;
        phase = Phase.SELECT;
        status = Component.translatable("integratedide.build.progress", stepIndex, steps.size());
        return true;
    }

    private boolean cleanupInput(ExpressionCompiler.CardStep step) {
        if (inputIndex >= step.inputs().size()) {
            phase = Phase.RETURN_OUTPUT;
            return true;
        }
        issue(() -> port.cleanupInput(inputIndex));
        phase = Phase.WAIT_INPUT_RETURNED;
        return false;
    }

    private boolean waitForInputReturned(ExpressionCompiler.CardStep step) {
        String input = step.inputs().get(inputIndex);
        if (!synchronizedAfterCommand() || !port.inputReturned(inputIndex, input)) {
            return waiting(Component.translatable("integratedide.build.wait.input_returned"));
        }
        inputIndex++;
        phase = Phase.CLEANUP_INPUT;
        return true;
    }

    private boolean waiting(Component message) {
        status = message;
        return false;
    }

    /**
     * Dispatch a container mutation and establish the exact client snapshot
     * that must be superseded before its postcondition may be observed.
     */
    private void issue(Runnable command) {
        commandRevision = port.synchronizationRevision();
        command.run();
    }

    private boolean synchronizedAfterCommand() {
        return port.synchronizationRevision() > commandRevision;
    }

    public static int countBlankVariableCards(Player player) {
        return CardInventory.countBlankVariableCards(player);
    }

    private void fail(String message) {
        fail(Component.literal(message));
    }

    private void fail(Component message) {
        String step = steps.isEmpty() ? "none" : Integer.toString(Math.min(stepIndex + 1, steps.size()));
        LOGGER.warn("Integrated IDE stopped card build at step {}/{} in {}: {}", step, steps.size(), phase,
                message.getString());
        phase = Phase.FAILED;
        status = Component.translatable("integratedide.build.stopped", message);
    }

    private enum Phase {
        IDLE, SELECT, CONFIGURE, WAIT_INPUT_SLOT, PICK_INPUT, WAIT_INPUT_HELD, PLACE_INPUT,
        WAIT_INPUT_PLACED, PICK_BLANK, WAIT_BLANK_HELD, PLACE_BLANK, WAIT_OUTPUT_READY,
        RETURN_REMAINDER, WAIT_REMAINDER_RETURNED, CLEANUP_INPUT, WAIT_INPUT_RETURNED,
        RETURN_OUTPUT, WAIT_OUTPUT_RETURNED, CONFIRM_OUTPUT, COMPLETE, CANCELLED, FAILED
    }
}
