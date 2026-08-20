package me.funclogic.integratedide.client;

import java.util.Map;
import me.funclogic.integratedide.expr.ExpressionCompiler;
import net.minecraft.world.item.ItemStack;

/**
 * The side-effect boundary for building Variable Cards. The driver owns the
 * sequencing rules; this port owns client menu packets and inventory state.
 */
interface CardBuildPort {
    boolean isCurrent();

    /** A newly synchronized server-side programmer error, or {@code null}. */
    String serverFailure();

    void select(ExpressionCompiler.CardStep step);

    void configure(ExpressionCompiler.CardStep step);

    void pickupInput(String stepId);

    boolean inputHeld(String stepId);

    boolean inputSlotReady(int inputIndex);

    void placeInput(int inputIndex);

    boolean inputPlaced(int inputIndex, String stepId);

    void pickupBlank();

    boolean blankHeld();

    void placeBlank();

    void returnBlankRemainder();

    boolean blankRemainderReturned();

    boolean outputReady();

    /** Return the completed write-slot card through the programmer's reset action. */
    void returnOutput();

    boolean outputReturned();

    void confirmOutput(String stepId);

    void cleanupInput(int inputIndex);

    boolean inputReturned(int inputIndex, String stepId);

    Map<String, ItemStack> producedCards();
}
