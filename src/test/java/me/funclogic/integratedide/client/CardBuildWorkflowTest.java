package me.funclogic.integratedide.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import me.funclogic.integratedide.expr.ExpressionCompiler;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

/**
 * Exercises the complete client build workflow with the live Integrated
 * Dynamics registry. The port models menu/inventory acknowledgements, while
 * {@link CardBuildDriver} is the unmodified production state machine.
 */
class CardBuildWorkflowTest {
    private static final String USER_EXPRESSION =
            "anyEquals(\"$minecraft:cobblestone\".withSize(10).size(), 10)";

    @Test
    void compilesWritesCachesAndReusesEveryCardForTheReportedExpression() {
        ExpressionCompiler.Compilation compilation = LogicProgrammerCatalog.create().compile(USER_EXPRESSION);
        assertTrue(compilation.valid(), compilation.message());
        assertEquals(6, compilation.steps().size(), "The expression must retain all intermediate cards.");

        InMemoryLogicProgrammer port = new InMemoryLogicProgrammer(12);
        CardBuildDriver driver = new CardBuildDriver(port, compilation.steps());
        driver.start();
        drain(driver);

        List<String> expectedIds = compilation.steps().stream().map(ExpressionCompiler.CardStep::id).toList();
        assertTrue(driver.isComplete(), driver.status());
        assertFalse(driver.isFailed(), driver.status());
        assertEquals(expectedIds, port.confirmedStepIds,
                "Every plan node must be confirmed in dependency order, not only the final expression.");
        assertEquals(new LinkedHashSet<>(expectedIds), driver.producedCards().keySet());
        assertEquals(expectedIds.size(), port.blankCardsConsumed);

        NovelCompilationCache.Reconciliation first = NovelCompilationCache.reconcile(compilation, List.of());
        Map<String, Integer> assignedIds = new LinkedHashMap<>();
        for (int index = 0; index < expectedIds.size(); index++) {
            assignedIds.put(expectedIds.get(index), 700 + index);
        }
        List<NovelCompilationCache.CachedNode> snapshot = NovelCompilationCache.snapshotWithCardIds(compilation, first,
                assignedIds);
        NovelCompilationCache.Reconciliation repeated = NovelCompilationCache.reconcile(compilation, snapshot);

        Map<Integer, ItemStack> backpack = new LinkedHashMap<>();
        for (int id : assignedIds.values()) {
            backpack.put(id, ItemStack.EMPTY);
        }
        NovelCompilationCache.BuildSelection reuse = NovelCompilationCache.select(compilation, repeated,
                (id, ignoredType) -> backpack.get(id), false);
        assertFalse(reuse.hasMissingExternal());
        assertFalse(reuse.needsRebuildConfirmation());
        assertTrue(reuse.stepsToBuild().isEmpty(), "An unchanged source must never create duplicate Variable Cards.");
        assertEquals(new LinkedHashSet<>(expectedIds), reuse.availableCards().keySet());
    }

    @Test
    void missingCachedCardRequiresConfirmationThenRebuildsItAndAllDependents() {
        ExpressionCompiler.Compilation compilation = LogicProgrammerCatalog.create().compile(USER_EXPRESSION);
        assertTrue(compilation.valid(), compilation.message());

        NovelCompilationCache.Reconciliation first = NovelCompilationCache.reconcile(compilation, List.of());
        Map<String, Integer> assignedIds = new LinkedHashMap<>();
        for (int index = 0; index < compilation.steps().size(); index++) {
            assignedIds.put(compilation.steps().get(index).id(), 800 + index);
        }
        NovelCompilationCache.Reconciliation cached = NovelCompilationCache.reconcile(compilation,
                NovelCompilationCache.snapshotWithCardIds(compilation, first, assignedIds));

        String missingStepId = compilation.steps().get(2).id();
        Set<Integer> presentIds = new LinkedHashSet<>(assignedIds.values());
        presentIds.remove(assignedIds.get(missingStepId));

        NovelCompilationCache.BuildSelection cautious = NovelCompilationCache.select(compilation, cached,
                (id, ignoredType) -> presentIds.contains(id) ? ItemStack.EMPTY : null, false);
        assertTrue(cautious.needsRebuildConfirmation());
        assertEquals(List.of(missingStepId), cautious.missingCachedNodes().stream()
                .map(missing -> missing.step().id()).toList());

        NovelCompilationCache.BuildSelection forced = NovelCompilationCache.select(compilation, cached,
                (id, ignoredType) -> presentIds.contains(id) ? ItemStack.EMPTY : null, true);
        List<String> rebuilt = forced.stepsToBuild().stream().map(ExpressionCompiler.CardStep::id).toList();
        assertTrue(rebuilt.contains(missingStepId));
        for (ExpressionCompiler.CardStep step : compilation.steps()) {
            if (step.inputs().contains(missingStepId)) {
                assertTrue(rebuilt.contains(step.id()), "Dependent " + step.id() + " was not invalidated.");
            }
        }

        InMemoryLogicProgrammer port = new InMemoryLogicProgrammer(12);
        port.preload(forced.availableCards());
        CardBuildDriver driver = new CardBuildDriver(port, forced.stepsToBuild());
        driver.start();
        drain(driver);
        assertTrue(driver.isComplete(), driver.status());
        assertEquals(rebuilt, port.confirmedStepIds,
                "Forced rebuild must write exactly the missing subgraph, not the whole unchanged program.");
    }

    @Test
    void executesAVirtualVariablePlanWithoutDuplicatingItsSharedIntermediateCard() {
        String source = "{stack} = \"$minecraft:cobblestone\".withSize(10)\n"
                + "anyEquals({stack}.size(), 10)";
        ExpressionCompiler.Compilation compilation = LogicProgrammerCatalog.create().compile(source);
        assertTrue(compilation.valid(), compilation.message());
        assertEquals(2, compilation.statementRoots().size());
        assertEquals(6, compilation.steps().size());

        InMemoryLogicProgrammer port = new InMemoryLogicProgrammer(12);
        CardBuildDriver driver = new CardBuildDriver(port, compilation.steps());
        driver.start();
        drain(driver);

        assertTrue(driver.isComplete(), driver.status());
        assertEquals(6, port.confirmedStepIds.size());
        assertEquals(6, new LinkedHashSet<>(port.confirmedStepIds).size(),
                "The virtual variable must reuse its already-created Variable Card.");
    }

    @Test
    void expandsRepeatedVirtualInputsBecauseOneCardCannotOccupyTwoProgrammerSlots() {
        String source = "{stack} = \"$minecraft:cobblestone\".withSize(10)\n"
                + "{alias} = {stack}\n"
                + "anyEquals({stack}, {alias})";
        ExpressionCompiler.Compilation compilation = LogicProgrammerCatalog.create().compile(source);
        assertTrue(compilation.valid(), compilation.message());

        ExpressionCompiler.CardStep root = compilation.steps().getLast();
        assertEquals(2, root.inputs().size());
        assertFalse(root.inputs().getFirst().equals(root.inputs().getLast()),
                "Two simultaneous inputs must be backed by two independently buildable cards.");

        InMemoryLogicProgrammer port = new InMemoryLogicProgrammer(12);
        CardBuildDriver driver = new CardBuildDriver(port, compilation.steps());
        driver.start();
        drain(driver);

        assertTrue(driver.isComplete(), driver.status());
        assertEquals(compilation.steps().size(), port.confirmedStepIds.size());
    }

    @Test
    void rejectsRepeatedExternalCardIdsBeforeTheDriverCanMoveAnyInventoryItem() {
        ExpressionCompiler.Compilation compilation = LogicProgrammerCatalog.create().compile("anyEquals({41}, {41})");

        assertFalse(compilation.valid());
        assertTrue(compilation.message().contains("External Variable Card {41}"), compilation.message());
    }

    @Test
    void stopsBeforeProducingAPartialPlanWhenThePortRunsOutOfBlankCards() {
        ExpressionCompiler.Compilation compilation = LogicProgrammerCatalog.create().compile(USER_EXPRESSION);
        assertTrue(compilation.valid(), compilation.message());

        InMemoryLogicProgrammer port = new InMemoryLogicProgrammer(2);
        CardBuildDriver driver = new CardBuildDriver(port, compilation.steps());
        driver.start();
        drain(driver);

        assertTrue(driver.isFailed());
        assertEquals(2, port.confirmedStepIds.size());
    }

    @Test
    void waitsForServerOutputInsteadOfFailingAfterAnArbitraryTickCount() {
        ExpressionCompiler.Compilation compilation = LogicProgrammerCatalog.create().compile(USER_EXPRESSION);
        assertTrue(compilation.valid(), compilation.message());

        InMemoryLogicProgrammer port = new InMemoryLogicProgrammer(12, 1);
        CardBuildDriver driver = new CardBuildDriver(port, compilation.steps());
        driver.start();
        advanceUntil(driver, () -> port.confirmedStepIds.size() == 1 && !port.outputReady);

        assertTrue(driver.isRunning());
        assertFalse(driver.isFailed());
        assertEquals(1, port.confirmedStepIds.size());
    }

    @Test
    void waitsForThirdAnyConstantCardInventorySyncInsteadOfFailingEarly() {
        ExpressionCompiler.Compilation compilation = LogicProgrammerCatalog.create().compile("anyConstant(1, 1)");
        assertTrue(compilation.valid(), compilation.message());
        assertEquals(3, compilation.steps().size(), "The reported expression must build two inputs and one result.");

        InMemoryLogicProgrammer port = new InMemoryLogicProgrammer(12, Integer.MAX_VALUE, 3);
        CardBuildDriver driver = new CardBuildDriver(port, compilation.steps());
        driver.start();
        advanceUntil(driver, port::awaitingDelayedInventorySync);
        // The preceding tick sent QUICK_MOVE. The following tick observes the
        // still-missing server inventory update and must enter its wait state.
        driver.tick();

        assertTrue(port.awaitingDelayedInventorySync(), "The third card must wait for the real inventory update.");
        assertTrue(driver.isRunning());
        assertFalse(driver.isFailed(), driver.status());
        assertEquals(2, port.confirmedStepIds.size(), "The first two cards must complete before the reported failure.");

        port.receiveDelayedInventorySync();
        drain(driver);

        assertTrue(driver.isComplete(), driver.status());
        assertEquals(3, port.confirmedStepIds.size());
    }

    @Test
    void stopsOnASynchronizedServerFailureWithoutUsingATimeout() {
        ExpressionCompiler.Compilation compilation = LogicProgrammerCatalog.create().compile("anyConstant(1, 1)");
        assertTrue(compilation.valid(), compilation.message());

        InMemoryLogicProgrammer port = new InMemoryLogicProgrammer(12);
        CardBuildDriver driver = new CardBuildDriver(port, compilation.steps());
        driver.start();
        port.serverFailure = "simulated server rejection";
        driver.tick();

        assertTrue(driver.isFailed());
    }

    @Test
    void cancellationStopsAutomationAndLeavesRecoveryToTheVanillaMenu() {
        ExpressionCompiler.Compilation compilation = LogicProgrammerCatalog.create().compile("anyConstant(1, 1)");
        assertTrue(compilation.valid(), compilation.message());

        CardBuildDriver driver = new CardBuildDriver(new InMemoryLogicProgrammer(12), compilation.steps());
        driver.start();
        driver.cancel();

        assertFalse(driver.isRunning());
        assertFalse(driver.isFailed());
        assertFalse(driver.isComplete());
    }

    private static void drain(CardBuildDriver driver) {
        for (int tick = 0; tick < 1_000 && driver.isRunning(); tick++) {
            driver.tick();
        }
        assertFalse(driver.isRunning(), "Card build did not reach a terminal state within 1,000 client ticks.");
    }

    /** Test safety guard only; production never uses elapsed ticks as a transition condition. */
    private static void advanceUntil(CardBuildDriver driver, BooleanSupplier reached) {
        for (int attempts = 0; attempts < 1_000 && driver.isRunning() && !reached.getAsBoolean(); attempts++) {
            driver.tick();
        }
        assertTrue(reached.getAsBoolean(), "The expected synchronized test state was never reached.");
    }

    private static final class InMemoryLogicProgrammer implements CardBuildPort {
        private final Map<String, ItemStack> cards = new LinkedHashMap<>();
        private final List<String> confirmedStepIds = new ArrayList<>();
        private final List<String> selectedInputs = new ArrayList<>();
        private final int maximumOutputs;
        private final int delayedInventoryOutputNumber;
        private int remainingBlankCards;
        private int blankCardsConsumed;
        private String activeStepId;
        private String heldInput;
        private boolean blankPickedUp;
        private boolean outputReady;
        private boolean outputMovedToInventory;
        private boolean delayedInventorySyncReceived;
        private String serverFailure;

        private InMemoryLogicProgrammer(int blankCards) {
            this(blankCards, Integer.MAX_VALUE, -1);
        }

        private InMemoryLogicProgrammer(int blankCards, int maximumOutputs) {
            this(blankCards, maximumOutputs, -1);
        }

        private InMemoryLogicProgrammer(int blankCards, int maximumOutputs, int delayedInventoryOutputNumber) {
            this.remainingBlankCards = blankCards;
            this.maximumOutputs = maximumOutputs;
            this.delayedInventoryOutputNumber = delayedInventoryOutputNumber;
        }

        private void preload(Map<String, ItemStack> existingCards) {
            cards.putAll(existingCards);
        }

        @Override
        public boolean isCurrent() {
            return true;
        }

        @Override
        public String serverFailure() {
            return serverFailure;
        }

        @Override
        public void select(ExpressionCompiler.CardStep step) {
            activeStepId = step.id();
            selectedInputs.clear();
            assertEquals(null, heldInput, "A previous input card was not returned to the player inventory.");
            blankPickedUp = false;
            outputReady = false;
            outputMovedToInventory = false;
        }

        @Override
        public void configure(ExpressionCompiler.CardStep step) {
            assertEquals(activeStepId, step.id());
            assertFalse(step.kind() == ExpressionCompiler.StepKind.DYNAMIC_OPERATOR);
        }

        @Override
        public void pickupInput(String stepId) {
            assertEquals(null, heldInput, "The programmer attempted to pick up two cards at once.");
            assertTrue(cards.containsKey(stepId), "Input card was requested before it was produced: " + stepId);
            cards.remove(stepId);
            heldInput = stepId;
        }

        @Override
        public boolean inputHeld(String stepId) {
            return stepId.equals(heldInput);
        }

        @Override
        public boolean inputSlotReady(int inputIndex) {
            return true;
        }

        @Override
        public void placeInput(int inputIndex) {
            assertEquals(inputIndex, selectedInputs.size());
            assertTrue(heldInput != null, "No input card was held for slot " + inputIndex);
            selectedInputs.add(heldInput);
            heldInput = null;
        }

        @Override
        public boolean inputPlaced(int inputIndex, String stepId) {
            return inputIndex < selectedInputs.size() && stepId.equals(selectedInputs.get(inputIndex));
        }

        @Override
        public void pickupBlank() {
            if (remainingBlankCards == 0) {
                throw new IllegalStateException("\u7a7a\u767d Variable Card \u5df2\u7528\u5c3d");
            }
            blankPickedUp = true;
        }

        @Override
        public boolean blankHeld() {
            return blankPickedUp;
        }

        @Override
        public void placeBlank() {
            assertTrue(blankPickedUp);
            outputReady = true;
        }

        @Override
        public void returnBlankRemainder() {
            assertTrue(blankPickedUp);
        }

        @Override
        public boolean blankRemainderReturned() {
            return blankPickedUp;
        }

        @Override
        public boolean outputReady() {
            return outputReady && confirmedStepIds.size() < maximumOutputs;
        }

        @Override
        public void storeOutput() {
            assertTrue(outputReady);
            // The write slot is empty after a shift-click. A normal test port
            // applies the inventory update immediately; the regression case
            // below deliberately leaves the moved card invisible until later.
            outputReady = false;
            outputMovedToInventory = true;
        }

        @Override
        public boolean outputStored() {
            return outputMovedToInventory
                    && (confirmedStepIds.size() + 1 != delayedInventoryOutputNumber || delayedInventorySyncReceived);
        }

        @Override
        public void confirmOutput(String stepId) {
            assertEquals(activeStepId, stepId);
            assertTrue(outputMovedToInventory, "The write-slot output was not moved before confirmation.");
            cards.put(stepId, ItemStack.EMPTY);
            confirmedStepIds.add(stepId);
            remainingBlankCards--;
            blankCardsConsumed++;
            outputMovedToInventory = false;
        }

        @Override
        public void cleanupInput(int inputIndex) {
            assertTrue(inputIndex < selectedInputs.size());
            String stepId = selectedInputs.get(inputIndex);
            assertFalse(cards.containsKey(stepId), "Input card should remain out of inventory until cleanup.");
            cards.put(stepId, ItemStack.EMPTY);
        }

        @Override
        public boolean inputReturned(int inputIndex, String stepId) {
            return inputIndex < selectedInputs.size() && stepId.equals(selectedInputs.get(inputIndex))
                    && cards.containsKey(stepId);
        }

        private boolean awaitingDelayedInventorySync() {
            return outputMovedToInventory && confirmedStepIds.size() + 1 == delayedInventoryOutputNumber
                    && !delayedInventorySyncReceived;
        }

        private void receiveDelayedInventorySync() {
            delayedInventorySyncReceived = true;
        }

        @Override
        public Map<String, ItemStack> producedCards() {
            return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(cards));
        }
    }
}
