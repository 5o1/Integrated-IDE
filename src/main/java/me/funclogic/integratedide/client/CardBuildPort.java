package me.funclogic.integratedide.client;

import java.util.Map;
import me.funclogic.integratedide.expr.ExpressionCompiler;
import net.minecraft.world.item.ItemStack;

/**
 * The side-effect boundary for building Variable Cards. The driver owns the
 * sequencing rules; this port owns client menu packets and inventory state.
 */
public interface CardBuildPort {
    boolean isCurrent();

    /**
     * Monotonically increases only when the client observes a newer
     * authoritative container or player-inventory synchronization state.
     *
     * <p>The driver records this value before mutations whose result is
     * created on the server. Ordinary container clicks use the client's
     * deterministic prediction and do not necessarily produce a reply.</p>
     */
    long synchronizationRevision();

    /** A newly synchronized server-side programmer error, or {@code null}. */
    String serverFailure();

    void select(ExpressionCompiler.CardStep step);

    void configure(ExpressionCompiler.CardStep step);

    void pickupInput(String stepId);

    boolean inputHeld(String stepId);

    boolean inputSlotReady(int inputIndex);

    void placeInput(int inputIndex);

    boolean inputPlaced(int inputIndex, String stepId);

    /**
     * Returns the Variable Card still held after programming a reference-style
     * input slot. Returns {@code true} only when a server container action was
     * sent; an already-empty cursor needs no synchronization wait.
     */
    boolean returnHeldInput();

    boolean inputCursorReturned();

    void pickupBlank();

    boolean blankHeld();

    void placeBlank();

    /**
     * Returns {@code true} only when a carried remainder required a server
     * container action. A one-card stack has no remainder and therefore no
     * synchronization event to wait for.
     */
    boolean returnBlankRemainder();

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
