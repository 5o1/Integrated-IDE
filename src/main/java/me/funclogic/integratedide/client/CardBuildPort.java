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

    void select(ExpressionCompiler.CardStep step);

    void configure(ExpressionCompiler.CardStep step);

    void pickupInput(String stepId);

    void placeInput(int inputIndex);

    void pickupBlank();

    void placeBlank();

    void returnBlankRemainder();

    boolean outputReady();

    void storeOutput();

    void confirmOutput(String stepId);

    void cleanupInput(int inputIndex);

    Map<String, ItemStack> producedCards();
}
