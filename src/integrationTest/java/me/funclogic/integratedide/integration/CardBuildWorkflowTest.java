package me.funclogic.integratedide.integration;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import me.funclogic.integratedide.client.CardBuildDriver;
import me.funclogic.integratedide.client.CardBuildPort;
import me.funclogic.integratedide.client.CardLiteralFactory;
import me.funclogic.integratedide.client.LogicProgrammerCatalog;
import me.funclogic.integratedide.client.LogicProgrammerMenuLayout;
import me.funclogic.integratedide.expr.ExpressionCompiler;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.inventory.RemoteSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import org.cyclops.integrateddynamics.RegistryEntries;
import org.cyclops.integrateddynamics.api.evaluate.operator.IOperator;
import org.cyclops.integrateddynamics.api.evaluate.variable.IValueType;
import org.cyclops.integrateddynamics.api.evaluate.variable.ValueDeseralizationContext;
import org.cyclops.integrateddynamics.api.item.IVariableFacade;
import org.cyclops.integrateddynamics.core.evaluate.operator.Operators;
import org.cyclops.integrateddynamics.core.evaluate.variable.ValueTypes;
import org.cyclops.integrateddynamics.core.logicprogrammer.LogicProgrammerElementTypes;
import org.cyclops.integrateddynamics.core.logicprogrammer.OperatorLPElement;
import org.cyclops.integrateddynamics.inventory.container.ContainerLogicProgrammer;
import org.cyclops.integrateddynamics.inventory.container.ContainerLogicProgrammerBase;
import org.cyclops.integrateddynamics.item.ItemVariable;
import org.cyclops.integrateddynamics.network.packet.LogicProgrammerActivateElementPacket;
import org.cyclops.integrateddynamics.network.packet.LogicProgrammerValueTypeBooleanValueChangedPacket;
import org.cyclops.integrateddynamics.network.packet.LogicProgrammerValueTypeIngredientsValueChangedPacket;
import org.cyclops.integrateddynamics.network.packet.LogicProgrammerValueTypeSlottedValueChangedPacket;
import org.cyclops.integrateddynamics.network.packet.LogicProgrammerValueTypeStringValueChangedPacket;

/**
 * End-to-end materialization tests. The test port deliberately owns no
 * success flags: every command enters Dynamic through its actual server
 * packet handler or {@link ContainerLogicProgrammerBase#clicked}, then the
 * driver can advance only after the test client applies the corresponding
 * {@link ContainerSynchronizer} update.
 */
class CardBuildWorkflowTest {
    private static final String USER_EXPRESSION =
            "anyEquals(\"$minecraft:cobblestone\".withSize(10).size(), 10)";

    static void verify(MinecraftServer server) {
        List<AssertionError> failures = new ArrayList<>();
        runCase(failures, "nested item-size expression",
                () -> materializesEveryCardForTheReportedExpressionThroughTheRealProgrammer(server));
        runCase(failures, "three-card anyConstant expression",
                () -> materializesAllThreeCardsForAnyConstantUsingActualPacketsAndInventoryClicks(server));
        runCase(failures, "reference input cursor protocol",
                () -> predictsTheReferenceInputClickThenWaitsForItsExplicitServerReturn(server));
        runCase(failures, "string literal card transport",
                () -> materializesLiteralThroughTheRealProgrammer(server, "anyEquals(\"plain\", \"plain\")",
                        ExpressionCompiler.StepKind.STATIC_TEXT));
        runCase(failures, "boolean literal card transport",
                () -> materializesLiteralThroughTheRealProgrammer(server, "anyEquals(true, true)",
                        ExpressionCompiler.StepKind.STATIC_BOOLEAN));
        runCase(failures, "mod literal card transport",
                () -> materializesLiteralThroughTheRealProgrammer(server, "anyEquals(\"@minecraft\", \"@minecraft\")",
                        ExpressionCompiler.StepKind.STATIC_MOD));
        runCase(failures, "fluid literal card transport",
                () -> materializesLiteralThroughTheRealProgrammer(server,
                        "anyEquals(\"$minecraft:water\", \"$minecraft:water\")",
                        ExpressionCompiler.StepKind.STATIC_FLUID));
        runCase(failures, "tag literal card transport",
                () -> materializesLiteralThroughTheRealProgrammer(server,
                        "anyEquals(\"#minecraft:planks\", \"#minecraft:planks\")",
                        ExpressionCompiler.StepKind.STATIC_TAG));
        runCase(failures, "separate one-card blank stacks",
                () -> materializesSeparateOneCardStacksWithoutWaitingForANonexistentRemainderSync(server));
        runCase(failures, "virtual-variable reuse",
                () -> reusesVirtualVariablesWithoutCreatingASecondSharedIntermediateCard(server));
        runCase(failures, "withheld output synchronization",
                () -> remainsWaitingUntilTheActualServerResultIsDeliveredAsANewSnapshot(server));
        runCase(failures, "reset packet omits stale write-slot delta",
                () -> reproducesTheStaleWriteSlotProtocolFailureWithoutLocalPrediction(server));
        runCase(failures, "insufficient third blank card",
                () -> stopsAfterTheRealProgrammerRejectsTheThirdBlankCard(server));
        if (!failures.isEmpty()) {
            AssertionError summary = new AssertionError("Logic Programmer workflow matrix failed in "
                    + failures.size() + " case(s).");
            failures.forEach(summary::addSuppressed);
            throw summary;
        }
    }

    private static void runCase(List<AssertionError> failures, String name, ServerCase test) {
        try {
            test.run();
        } catch (RuntimeException | AssertionError error) {
            failures.add(new AssertionError(name + ": " + error.getMessage(), error));
        }
    }

    private static void materializesEveryCardForTheReportedExpressionThroughTheRealProgrammer(MinecraftServer server) {
        BuildRun run = start(server, USER_EXPRESSION, 12);

        drainAfterEveryServerSnapshot(run);

        assertTrue(run.driver.isComplete(), run.driver.status());
        assertFalse(run.driver.isFailed(), run.driver.status());
        assertEquals(6, run.port.confirmedStepIds.size());
        assertEquals(run.compilation.steps().stream().map(ExpressionCompiler.CardStep::id).toList(),
                run.port.confirmedStepIds);
        assertEquals(6, run.port.returnOutputRequests);
        assertEquals(6, run.port.producedCards().size());
        run.port.producedCards().forEach((step, card) -> assertValidVariableCard(run.port.level, card,
                "Step " + step + " did not result in a valid Variable Card."));
    }

    private static void materializesAllThreeCardsForAnyConstantUsingActualPacketsAndInventoryClicks(MinecraftServer server) {
        BuildRun run = start(server, "anyConstant(1, 1)", 3);

        drainAfterEveryServerSnapshot(run);

        assertTrue(run.driver.isComplete(), run.driver.status());
        assertEquals(List.of("v0", "v1", "v2"), run.port.confirmedStepIds);
        assertEquals(3, run.port.validCardsInPlayerInventory());
    }

    /** Runs every literal transport through Dynamic's packet handler and write slot, not a dispatcher mock. */
    private static void materializesLiteralThroughTheRealProgrammer(MinecraftServer server, String source,
                                                                     ExpressionCompiler.StepKind literalKind) {
        BuildRun run = start(server, source, 3);

        assertTrue(run.compilation.steps().stream().anyMatch(step -> step.kind() == literalKind),
                "The real catalog did not lower " + literalKind + " for " + source);
        drainAfterEveryServerSnapshot(run);

        assertTrue(run.driver.isComplete(), run.driver.status());
        assertEquals(3, run.port.confirmedStepIds.size());
        run.port.producedCards().forEach((step, card) -> assertValidVariableCard(run.port.level, card,
                "Literal step " + step + " did not produce a valid Variable Card."));
    }

    /**
     * Dynamic operator inputs are references: clicking their slot keeps the
     * source Variable Card on the cursor while the slot records that
     * reference. The live client predicts that click before its packet reaches
     * the server. This assertion prevents the harness from modelling only the
     * server half of a container click and accidentally requiring an empty
     * cursor at the wrong transition.
     */
    private static void predictsTheReferenceInputClickThenWaitsForItsExplicitServerReturn(MinecraftServer server) {
        BuildRun run = start(server, "anyConstant(1, 1)", 3);

        advanceUntilFirstInputPlacementIsSent(run);

        assertTrue(run.port.clientInputContainsPendingReference(),
                "The client prediction must show the reference in Dynamic's first input slot.");
        assertTrue(run.port.clientCursorStillContainsPendingReference(),
                "A reference input must leave the source Variable Card on the client cursor.");

        long revisionBeforePlacementFlush = run.port.synchronizationRevision();
        run.port.flushServerChanges();
        assertTrue(run.port.synchronizationRevision() > revisionBeforePlacementFlush,
                "Dynamic must confirm the reference stored in its temporary input slot.");
        run.driver.tick();
        assertTrue(run.port.hasSentInputCursorReturn(),
                "The driver must return the predicted cursor card through a separate container click.");
        long revisionBeforeReturnFlush = run.port.synchronizationRevision();
        run.port.flushServerChanges();
        assertTrue(run.port.synchronizationRevision() > revisionBeforeReturnFlush,
                "Dynamic must confirm the returned input card in the player inventory.");
        assertTrue(run.port.clientCursorIsEmpty(),
                "The second client prediction must return the input card to its inventory slot.");
        run.driver.tick();
        drainAfterEveryServerSnapshot(run);
        assertTrue(run.driver.isComplete(), run.driver.status());
    }

    private static void materializesSeparateOneCardStacksWithoutWaitingForANonexistentRemainderSync(MinecraftServer server) {
        BuildRun run = startWithSeparateBlankStacks(server, "anyConstant(1, 1)", 3);

        drainAfterEveryServerSnapshot(run);

        assertTrue(run.driver.isComplete(), run.driver.status());
        assertEquals(List.of("v0", "v1", "v2"), run.port.confirmedStepIds);
    }

    private static void reusesVirtualVariablesWithoutCreatingASecondSharedIntermediateCard(MinecraftServer server) {
        BuildRun run = start(server, "{stack} = \"$minecraft:cobblestone\".withSize(10)\n"
                + "anyEquals({stack}.size(), 10)", 12);

        drainAfterEveryServerSnapshot(run);

        assertTrue(run.driver.isComplete(), run.driver.status());
        assertEquals(run.compilation.steps().size(), run.port.confirmedStepIds.size());
        assertEquals(run.compilation.steps().size(), run.port.validCardsInPlayerInventory());
    }

    private static void remainsWaitingUntilTheActualServerResultIsDeliveredAsANewSnapshot(MinecraftServer server) {
        BuildRun run = start(server, "anyConstant(1, 1)", 3);

        advanceUntilFirstOutputReturnIsSent(run);
        run.driver.tick();

        assertTrue(run.driver.isRunning(), "The driver must not confirm a locally predicted output.");
        assertFalse(run.driver.isComplete());
        assertTrue(run.port.serverHasReturnedCurrentOutput(),
                "The real Dynamic reset packet must have emptied the server write slot first.");

        run.port.flushServerChanges();
        drainAfterEveryServerSnapshot(run);

        assertTrue(run.driver.isComplete(), run.driver.status());
    }

    /**
     * This guards the exact protocol edge case behind the original in-game
     * stall: the Dynamic reset rebuilds its remote slots after clearing the
     * write slot, so no empty-write delta is necessarily sent. The returned
     * card does arrive in the client inventory, but an unpredicted local write
     * slot still contains the stale result and must keep the driver waiting.
     */
    private static void reproducesTheStaleWriteSlotProtocolFailureWithoutLocalPrediction(MinecraftServer server) {
        BuildRun run = start(server, "anyConstant(1, 1)", 3, false);

        advanceUntilFirstOutputReturnIsSent(run);
        run.port.flushServerChanges();
        run.driver.tick();

        assertTrue(run.port.serverHasReturnedCurrentOutput(),
                "The server must return the result even when it omits the redundant write-slot delta.");
        assertTrue(run.port.clientWriteStillContainsPendingOutput(),
                "The unpredicted client write slot must reproduce the stale-card protocol state.");
        assertTrue(run.driver.isRunning(), "The driver must not confirm a stale local write slot as a completed card.");
    }

    private static void stopsAfterTheRealProgrammerRejectsTheThirdBlankCard(MinecraftServer server) {
        BuildRun run = start(server, "anyConstant(1, 1)", 2);

        drainAfterEveryServerSnapshot(run);

        assertTrue(run.driver.isFailed());
        assertEquals(List.of("v0", "v1"), run.port.confirmedStepIds,
                "The server must retain the two already-created cards and reject only the absent third blank card.");
    }

    private static BuildRun start(MinecraftServer server, String source, int blankCards) {
        return start(server, source, blankCards, true);
    }

    private static BuildRun start(MinecraftServer server, String source, int blankCards, boolean predictOutputClear) {
        ExpressionCompiler.Compilation compilation = LogicProgrammerCatalog.create().compile(source);
        assertTrue(compilation.valid(), compilation.message());
        ServerDrivenProgrammer port = ServerDrivenProgrammer.open(server, blankCards, false, predictOutputClear);
        CardBuildDriver driver = new CardBuildDriver(port, compilation.steps());
        driver.start();
        return new BuildRun(compilation, port, driver);
    }

    private static BuildRun startWithSeparateBlankStacks(MinecraftServer server, String source, int blankCards) {
        ExpressionCompiler.Compilation compilation = LogicProgrammerCatalog.create().compile(source);
        assertTrue(compilation.valid(), compilation.message());
        ServerDrivenProgrammer port = ServerDrivenProgrammer.open(server, blankCards, true);
        CardBuildDriver driver = new CardBuildDriver(port, compilation.steps());
        driver.start();
        return new BuildRun(compilation, port, driver);
    }

    private static void drainAfterEveryServerSnapshot(BuildRun run) {
        while (run.driver.isRunning()) {
            long revisionBeforeTick = run.port.synchronizationRevision();
            int commandsBeforeTick = run.port.commandCount();
            run.driver.tick();
            if (!run.driver.isRunning()) {
                break;
            }
            run.port.flushServerChanges();
            if (run.port.synchronizationRevision() == revisionBeforeTick
                    && run.port.commandCount() == commandsBeforeTick) {
                throw new AssertionError("The driver is waiting for an authoritative server result, but Dynamic "
                        + "emitted no container synchronization; driver=" + run.driver.status() + ". "
                        + run.port.describeState());
            }
        }
    }

    private static void advanceUntilFirstOutputReturnIsSent(BuildRun run) {
        while (run.port.returnOutputRequests == 0) {
            long revisionBeforeTick = run.port.synchronizationRevision();
            int commandsBeforeTick = run.port.commandCount();
            run.driver.tick();
            if (run.port.returnOutputRequests == 0) {
                run.port.flushServerChanges();
                if (run.port.synchronizationRevision() == revisionBeforeTick
                        && run.port.commandCount() == commandsBeforeTick) {
                    throw new AssertionError("The server emitted no container synchronization before the first "
                            + "output return. " + run.port.describeState());
                }
            }
        }
        assertEquals(1, run.port.returnOutputRequests,
                "The test did not reach the first real reset-packet transition.");
    }

    private static void advanceUntilFirstInputPlacementIsSent(BuildRun run) {
        while (!run.port.hasSentFirstInputPlacement()) {
            long revisionBeforeTick = run.port.synchronizationRevision();
            int commandsBeforeTick = run.port.commandCount();
            run.driver.tick();
            if (!run.port.hasSentFirstInputPlacement()) {
                run.port.flushServerChanges();
                if (run.port.synchronizationRevision() == revisionBeforeTick
                        && run.port.commandCount() == commandsBeforeTick) {
                    throw new AssertionError("The server emitted no container synchronization before the first "
                            + "reference-input placement. " + run.port.describeState());
                }
            }
        }
    }

    private static void assertValidVariableCard(ServerLevel level, ItemStack stack, String message) {
        assertTrue(stack.getItem() instanceof ItemVariable, message);
        IVariableFacade facade = ((ItemVariable) stack.getItem()).getVariableFacade(ValueDeseralizationContext.of(level), stack);
        assertTrue(facade.isValid(), message);
        assertTrue(facade.getId() >= 0, message);
    }

    private record BuildRun(ExpressionCompiler.Compilation compilation, ServerDrivenProgrammer port,
                            CardBuildDriver driver) {
    }

    @FunctionalInterface
    private interface ServerCase {
        void run();
    }

    /**
     * A loopback client/server boundary for the actual Dynamic menu. Commands
     * call Dynamic's server packet/menu entry points; predicates read only the
     * latest packet-applied client mirror, never mutable server slot contents.
     */
    private static final class ServerDrivenProgrammer implements CardBuildPort {
        private static final Identifier EMPTY_ELEMENT_ID = Identifier.parse("");

        private final ServerLevel level;
        private final ServerPlayer serverPlayer;
        private final ServerPlayer clientPlayer;
        private final ContainerLogicProgrammer serverMenu;
        private final ContainerLogicProgrammer clientMenu;
        private final boolean predictOutputClear;
        private final Map<String, ItemStack> produced = new LinkedHashMap<>();
        private final Map<Integer, Integer> placedInputIds = new LinkedHashMap<>();
        private final List<String> confirmedStepIds = new ArrayList<>();
        private final List<String> synchronizationTrace = new ArrayList<>();
        private ObservedMenuState snapshot;
        private long synchronizationRevision;
        private int pendingInputId = -1;
        private int pendingOutputId = -1;
        private int inputSourceSlot = -1;
        private int blankSourceSlot = -1;
        private String errorBeforeAction;
        private int returnOutputRequests;
        private int commandCount;
        private String lastCommand = "initial menu state";

        private ServerDrivenProgrammer(ServerLevel level, ServerPlayer serverPlayer, ServerPlayer clientPlayer,
                                       ContainerLogicProgrammer serverMenu, ContainerLogicProgrammer clientMenu,
                                       boolean predictOutputClear) {
            this.level = level;
            this.serverPlayer = serverPlayer;
            this.clientPlayer = clientPlayer;
            this.serverMenu = serverMenu;
            this.clientMenu = clientMenu;
            this.predictOutputClear = predictOutputClear;
            this.snapshot = ObservedMenuState.empty(clientMenu.slots.size());
            // This is the server's actual container-delta boundary. The test
            // driver can see a slot only after AbstractContainerMenu has
            // emitted it through this synchronizer, never by inspecting the
            // mutable server menu directly.
            this.serverMenu.setSynchronizer(new LoopbackMenuSynchronizer(this));
        }

        static ServerDrivenProgrammer open(MinecraftServer server, int blankCards, boolean separateStacks) {
            return open(server, blankCards, separateStacks, true);
        }

        static ServerDrivenProgrammer open(MinecraftServer server, int blankCards, boolean separateStacks,
                                           boolean predictOutputClear) {
            ServerLevel level = server.overworld();
            ServerPlayer serverPlayer = FakePlayerFactory.get(level,
                    new GameProfile(UUID.randomUUID(), "integratedide_junit_server"));
            ServerPlayer clientPlayer = FakePlayerFactory.get(level,
                    new GameProfile(UUID.randomUUID(), "integratedide_junit_client"));
            serverPlayer.getInventory().clearContent();
            clientPlayer.getInventory().clearContent();
            if (separateStacks) {
                for (int slot = 0; slot < blankCards; slot++) {
                    serverPlayer.getInventory().setItem(slot, new ItemStack(RegistryEntries.ITEM_VARIABLE.get()));
                }
            } else {
                serverPlayer.getInventory().setItem(0, new ItemStack(RegistryEntries.ITEM_VARIABLE.get(), blankCards));
            }
            ContainerLogicProgrammer serverMenu = new ContainerLogicProgrammer(91, serverPlayer.getInventory());
            ContainerLogicProgrammer clientMenu = new ContainerLogicProgrammer(91, clientPlayer.getInventory());
            serverPlayer.containerMenu = serverMenu;
            clientPlayer.containerMenu = clientMenu;
            return new ServerDrivenProgrammer(level, serverPlayer, clientPlayer, serverMenu, clientMenu,
                    predictOutputClear);
        }

        void flushServerChanges() {
            serverMenu.broadcastChanges();
            assertClientLayoutMatchesServer();
        }

        private void receiveInitialState(int stateId, List<ItemStack> slots, ItemStack carried) {
            clientMenu.initializeContents(stateId, slots, carried);
            snapshot = ObservedMenuState.capture(clientMenu);
            synchronizationRevision++;
            trace("initial state=" + stateId + ", slots=" + slots.size() + ", carried=" + describe(carried));
        }

        private void receiveSlot(int stateId, int slot, ItemStack stack) {
            clientMenu.setItem(slot, stateId, stack);
            snapshot = ObservedMenuState.capture(clientMenu);
            synchronizationRevision++;
            trace("slot state=" + stateId + ", index=" + slot + ", value=" + describe(stack));
        }

        private void receiveCarried(ItemStack carried) {
            clientMenu.setCarried(carried);
            snapshot = ObservedMenuState.capture(clientMenu);
            synchronizationRevision++;
            trace("carried=" + describe(carried));
        }

        int commandCount() {
            return commandCount;
        }

        String describeState() {
            ItemStack clientWrite = snapshot.slot(LogicProgrammerMenuLayout.writeSlot(clientMenu));
            return "last command=" + lastCommand + ", revision=" + synchronizationRevision
                    + ", client write=" + describe(clientWrite) + ", output id=" + pendingOutputId
                    + ", client received=" + describe(findReceivedVariableCard(pendingOutputId))
                    + ", server received=" + describe(findServerVariableCard(pendingOutputId))
                    + ", trace=" + synchronizationTrace;
        }

        boolean serverHasReturnedCurrentOutput() {
            return serverMenu.slots.get(LogicProgrammerMenuLayout.writeSlot(serverMenu)).getItem().isEmpty()
                    && findServerVariableCard(pendingOutputId) != null;
        }

        boolean clientWriteStillContainsPendingOutput() {
            return variableCardId(snapshot.slot(LogicProgrammerMenuLayout.writeSlot(clientMenu))) == pendingOutputId;
        }

        int validCardsInPlayerInventory() {
            int cards = 0;
            for (int slot : playerInventorySlots(clientMenu, clientPlayer)) {
                ItemStack stack = snapshot.slot(slot);
                if (isValidVariable(stack)) {
                    cards += stack.getCount();
                }
            }
            return cards;
        }

        boolean hasSentFirstInputPlacement() {
            return "place input 1".equals(lastCommand);
        }

        boolean clientInputContainsPendingReference() {
            if (!hasSentFirstInputPlacement()) {
                return false;
            }
            int inputSlot = LogicProgrammerMenuLayout.inputSlot(clientMenu, 0);
            return variableCardId(snapshot.slot(inputSlot)) == pendingInputId;
        }

        boolean clientCursorStillContainsPendingReference() {
            return variableCardId(snapshot.carried) == pendingInputId;
        }

        boolean hasSentInputCursorReturn() {
            return "return held input".equals(lastCommand);
        }

        boolean clientCursorIsEmpty() {
            return snapshot.carried.isEmpty();
        }

        @Override
        public boolean isCurrent() {
            return serverPlayer.containerMenu == serverMenu && clientPlayer.containerMenu == clientMenu;
        }

        @Override
        public long synchronizationRevision() {
            return synchronizationRevision;
        }

        @Override
        public String serverFailure() {
            String currentError = errorText();
            if (errorBeforeAction == null || currentError.isBlank() || currentError.equals(errorBeforeAction)) {
                return null;
            }
            return currentError;
        }

        @Override
        public void select(ExpressionCompiler.CardStep step) {
            beforeServerAction("select " + step.id());
            if (step.kind() == ExpressionCompiler.StepKind.DYNAMIC_OPERATOR) {
                Identifier operatorId = Identifier.parse(step.value());
                IOperator operator = Operators.REGISTRY.getOperator(operatorId);
                assertNotNull(operator, "Dynamic did not register operator " + step.value());
                OperatorLPElement element = new OperatorLPElement(operator);
                Identifier typeId = LogicProgrammerElementTypes.OPERATOR.getUniqueName();
                Identifier elementId = LogicProgrammerElementTypes.OPERATOR.getName(element);
                clientMenu.setActiveElementById(typeId, elementId);
                new LogicProgrammerActivateElementPacket(typeId, elementId).actionServer(level, serverPlayer);
                return;
            }
            if (step.kind() == ExpressionCompiler.StepKind.EXTERNAL_REFERENCE) {
                return;
            }
            Identifier typeId = Identifier.parse(step.outputTypeId());
            IValueType<?> valueType = ValueTypes.REGISTRY.getValueType(typeId);
            assertNotNull(valueType, "Dynamic did not register value type " + step.outputTypeId());
            var element = valueType.createLogicProgrammerElement();
            Identifier elementType = LogicProgrammerElementTypes.VALUETYPE.getUniqueName();
            Identifier elementId = LogicProgrammerElementTypes.VALUETYPE.getName(element);
            // Ingredients exposes a client-only element implementation. The
            // headless server harness cannot instantiate it, but static
            // ingredient cards have no temporary input slots, so their
            // container topology is identical to the already mirrored empty
            // client layout. The actual server selection and value packet
            // below are still exercised.
            if (step.kind() != ExpressionCompiler.StepKind.STATIC_TAG) {
                clientMenu.setActiveElementById(elementType, elementId);
            }
            new LogicProgrammerActivateElementPacket(elementType, elementId).actionServer(level, serverPlayer);
        }

        @Override
        public void configure(ExpressionCompiler.CardStep step) {
            beforeServerAction("configure " + step.id());
            switch (step.kind()) {
                case STATIC_TEXT, STATIC_MOD -> new LogicProgrammerValueTypeStringValueChangedPacket(step.value())
                        .actionServer(level, serverPlayer);
                case STATIC_BOOLEAN -> new LogicProgrammerValueTypeBooleanValueChangedPacket(Boolean.parseBoolean(step.value()))
                        .actionServer(level, serverPlayer);
                case STATIC_ITEM -> new LogicProgrammerValueTypeSlottedValueChangedPacket(CardLiteralFactory.itemStack(step.value()))
                        .actionServer(level, serverPlayer);
                case STATIC_FLUID -> new LogicProgrammerValueTypeSlottedValueChangedPacket(CardLiteralFactory.fluidBucket(step.value()))
                        .actionServer(level, serverPlayer);
                case STATIC_TAG -> new LogicProgrammerValueTypeIngredientsValueChangedPacket(ValueDeseralizationContext.of(level),
                        CardLiteralFactory.ingredientsTag(step.value())).actionServer(level, serverPlayer);
                case DYNAMIC_OPERATOR, EXTERNAL_REFERENCE -> throw new IllegalArgumentException(
                        "Only static literal steps may be configured.");
            }
        }

        @Override
        public void pickupInput(String stepId) {
            ItemStack input = produced.get(stepId);
            if (input == null) {
                throw new IllegalStateException("Missing produced input card " + stepId);
            }
            int inputId = variableCardId(input);
            int sourceSlot = findVariableCardSlot(inputId);
            if (sourceSlot < 0) {
                throw new IllegalStateException("The synchronized inventory no longer contains input " + stepId);
            }
            pendingInputId = inputId;
            inputSourceSlot = sourceSlot;
            click("pick input " + stepId, sourceSlot, 0, ContainerInput.PICKUP);
        }

        @Override
        public boolean inputHeld(String stepId) {
            ItemStack expected = produced.get(stepId);
            return expected != null && variableCardId(snapshot.carried) == variableCardId(expected);
        }

        @Override
        public boolean inputSlotReady(int inputIndex) {
            return inputIndex >= 0 && inputIndex < LogicProgrammerMenuLayout.inputSlotCount(clientMenu);
        }

        @Override
        public void placeInput(int inputIndex) {
            int target = LogicProgrammerMenuLayout.inputSlot(clientMenu, inputIndex);
            placedInputIds.put(inputIndex, pendingInputId);
            click("place input " + (inputIndex + 1), target, 0, ContainerInput.PICKUP);
        }

        @Override
        public boolean inputPlaced(int inputIndex, String stepId) {
            Integer expectedId = placedInputIds.get(inputIndex);
            if (expectedId == null || !inputSlotReady(inputIndex)) {
                return false;
            }
            int target = LogicProgrammerMenuLayout.inputSlot(clientMenu, inputIndex);
            boolean placed = variableCardId(snapshot.slot(target)) == expectedId;
            if (!placed) {
                trace("input " + (inputIndex + 1) + " pending: expected id=" + expectedId + ", slot="
                        + variableCardId(snapshot.slot(target)) + ", carried=" + describe(snapshot.carried));
            }
            return placed;
        }

        @Override
        public boolean returnHeldInput() {
            if (snapshot.carried.isEmpty()) {
                inputSourceSlot = -1;
                return false;
            }
            if (inputSourceSlot < 0) {
                throw new IllegalStateException("No source slot is available for the held input card.");
            }
            click("return held input", inputSourceSlot, 0, ContainerInput.PICKUP);
            inputSourceSlot = -1;
            return true;
        }

        @Override
        public boolean inputCursorReturned() {
            return snapshot.carried.isEmpty();
        }

        @Override
        public void pickupBlank() {
            blankSourceSlot = findBlankSlot();
            if (blankSourceSlot < 0) {
                throw new IllegalStateException("Blank Variable Cards are exhausted.");
            }
            click("pick blank card", blankSourceSlot, 0, ContainerInput.PICKUP);
        }

        @Override
        public boolean blankHeld() {
            return isBlankVariable(snapshot.carried);
        }

        @Override
        public void placeBlank() {
            click("place blank card", LogicProgrammerMenuLayout.writeSlot(clientMenu), 1, ContainerInput.PICKUP);
        }

        @Override
        public boolean returnBlankRemainder() {
            if (snapshot.carried.isEmpty()) {
                blankSourceSlot = -1;
                return false;
            }
            if (blankSourceSlot < 0) {
                throw new IllegalStateException("No source slot is available for the remaining blank cards.");
            }
            click("return blank remainder", blankSourceSlot, 0, ContainerInput.PICKUP);
            blankSourceSlot = -1;
            return true;
        }

        @Override
        public boolean blankRemainderReturned() {
            return snapshot.carried.isEmpty();
        }

        @Override
        public boolean outputReady() {
            ItemStack output = snapshot.slot(LogicProgrammerMenuLayout.writeSlot(clientMenu));
            if (output.isEmpty() || isBlankVariable(output)) {
                return false;
            }
            int outputId = variableCardId(output);
            if (outputId < 0) {
                return false;
            }
            pendingOutputId = outputId;
            return true;
        }

        @Override
        public void returnOutput() {
            beforeServerAction("return output");
            returnOutputRequests++;
            // This is the same topology prediction performed by
            // LogicProgrammerGateway. It is intentionally not a successful
            // build signal; outputReturned still reads only packet-applied
            // client inventory state.
            if (predictOutputClear) {
                clientMenu.slots.get(LogicProgrammerMenuLayout.writeSlot(clientMenu)).set(ItemStack.EMPTY);
            }
            clientMenu.setActiveElementById(EMPTY_ELEMENT_ID, EMPTY_ELEMENT_ID);
            new LogicProgrammerActivateElementPacket(EMPTY_ELEMENT_ID, EMPTY_ELEMENT_ID).actionServer(level, serverPlayer);
        }

        @Override
        public boolean outputReturned() {
            return pendingOutputId >= 0
                    && snapshot.slot(LogicProgrammerMenuLayout.writeSlot(clientMenu)).isEmpty()
                    && findReceivedVariableCard(pendingOutputId) != null;
        }

        @Override
        public void confirmOutput(String stepId) {
            ItemStack stored = findReceivedVariableCard(pendingOutputId);
            if (stored == null) {
                throw new IllegalStateException("The returned output was absent from the synchronized inventory.");
            }
            produced.put(stepId, stored.copy());
            confirmedStepIds.add(stepId);
            pendingOutputId = -1;
            placedInputIds.clear();
        }

        @Override
        public Map<String, ItemStack> producedCards() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(produced));
        }

        private void beforeServerAction(String action) {
            errorBeforeAction = errorText();
            commandCount++;
            lastCommand = action;
            trace("command " + commandCount + ": " + action);
        }

        /**
         * Mirrors {@code MultiPlayerGameMode.handleContainerInput}: apply the
         * deterministic client-side click prediction first, then dispatch the
         * identical click to the real Dynamic server menu. Local prediction
         * intentionally does not increment {@link #synchronizationRevision};
         * only the loopback synchronizer is an acknowledgement.
         */
        private void click(String action, int slot, int button, ContainerInput input) {
            beforeServerAction(action);
            clientMenu.clicked(slot, button, input, clientPlayer);
            snapshot = ObservedMenuState.capture(clientMenu);
            trace("client prediction: " + action + ", carried=" + describe(snapshot.carried));
            serverMenu.clicked(slot, button, input, serverPlayer);
        }

        private void trace(String event) {
            if (synchronizationTrace.size() == 32) {
                synchronizationTrace.removeFirst();
            }
            synchronizationTrace.add(event);
        }

        private static String describe(ItemStack stack) {
            if (stack == null) {
                return "none";
            }
            if (stack.isEmpty()) {
                return "empty";
            }
            return stack.getItem() + " x" + stack.getCount() + " " + stack.getComponentsPatch();
        }

        private int findBlankSlot() {
            for (int index = 0; index < clientMenu.slots.size(); index++) {
                Slot slot = clientMenu.slots.get(index);
                if (slot.container == clientPlayer.getInventory() && isBlankVariable(slot.getItem())) {
                    return index;
                }
            }
            return -1;
        }

        private int findVariableCardSlot(int variableId) {
            for (int index = 0; index < clientMenu.slots.size(); index++) {
                Slot slot = clientMenu.slots.get(index);
                if (slot.container == clientPlayer.getInventory() && variableCardId(slot.getItem()) == variableId) {
                    return index;
                }
            }
            return -1;
        }

        private ItemStack findReceivedVariableCard(int variableId) {
            if (variableId < 0) {
                return null;
            }
            for (int slot : playerInventorySlots(clientMenu, clientPlayer)) {
                ItemStack stack = snapshot.slot(slot);
                if (variableCardId(stack) == variableId) {
                    return stack;
                }
            }
            return null;
        }

        private ItemStack findServerVariableCard(int variableId) {
            if (variableId < 0) {
                return null;
            }
            for (ItemStack stack : serverPlayer.getInventory().getNonEquipmentItems()) {
                if (variableCardId(stack) == variableId) {
                    return stack;
                }
            }
            return null;
        }

        private String errorText() {
            return serverMenu.getLastError() == null ? "" : serverMenu.getLastError().getString();
        }

        private void assertClientLayoutMatchesServer() {
            int serverInputs = LogicProgrammerMenuLayout.inputSlotCount(serverMenu);
            int clientInputs = LogicProgrammerMenuLayout.inputSlotCount(clientMenu);
            int serverWrite = LogicProgrammerMenuLayout.writeSlot(serverMenu);
            int clientWrite = LogicProgrammerMenuLayout.writeSlot(clientMenu);
            if (serverMenu.slots.size() != clientMenu.slots.size() || serverInputs != clientInputs
                    || serverWrite != clientWrite) {
                throw new AssertionError("Client Logic Programmer layout diverged after server synchronization: "
                        + "server slots=" + serverMenu.slots.size() + ", inputs=" + serverInputs + ", write=" + serverWrite
                        + "; client slots=" + clientMenu.slots.size() + ", inputs=" + clientInputs + ", write=" + clientWrite);
            }
        }

        private static List<Integer> playerInventorySlots(ContainerLogicProgrammerBase menu, ServerPlayer player) {
            List<Integer> slots = new ArrayList<>();
            for (int index = 0; index < menu.slots.size(); index++) {
                if (menu.slots.get(index).container == player.getInventory()) {
                    slots.add(index);
                }
            }
            return List.copyOf(slots);
        }

        private boolean isBlankVariable(ItemStack stack) {
            if (!(stack.getItem() instanceof ItemVariable variable)) {
                return false;
            }
            return !variable.getVariableFacade(ValueDeseralizationContext.of(level), stack).isValid();
        }

        private boolean isValidVariable(ItemStack stack) {
            if (!(stack.getItem() instanceof ItemVariable variable)) {
                return false;
            }
            return variable.getVariableFacade(ValueDeseralizationContext.of(level), stack).isValid();
        }

        private int variableCardId(ItemStack stack) {
            if (!(stack.getItem() instanceof ItemVariable variable)) {
                return -1;
            }
            IVariableFacade facade = variable.getVariableFacade(ValueDeseralizationContext.of(level), stack);
            return facade.isValid() ? facade.getId() : -1;
        }
    }

    private static final class ObservedMenuState {
        private final List<ItemStack> slots;
        private final ItemStack carried;

        private ObservedMenuState(List<ItemStack> slots, ItemStack carried) {
            this.slots = slots.stream().map(ItemStack::copy).toList();
            this.carried = carried.copy();
        }

        static ObservedMenuState empty(int slotCount) {
            List<ItemStack> emptySlots = new ArrayList<>(slotCount);
            for (int index = 0; index < slotCount; index++) {
                emptySlots.add(ItemStack.EMPTY);
            }
            return new ObservedMenuState(emptySlots, ItemStack.EMPTY);
        }

        static ObservedMenuState capture(ContainerLogicProgrammerBase menu) {
            return new ObservedMenuState(menu.slots.stream().map(slot -> slot.getItem().copy()).toList(),
                    menu.getCarried());
        }

        ItemStack slot(int index) {
            return index >= 0 && index < slots.size() ? slots.get(index) : ItemStack.EMPTY;
        }

    }

    /**
     * Mirrors exactly the container messages a connected player receives.
     * It deliberately exposes no path that reads a slot from the server menu.
     */
    private static final class LoopbackMenuSynchronizer implements ContainerSynchronizer {
        private final ServerDrivenProgrammer client;

        private LoopbackMenuSynchronizer(ServerDrivenProgrammer client) {
            this.client = client;
        }

        @Override
        public void sendInitialData(net.minecraft.world.inventory.AbstractContainerMenu container,
                                    List<ItemStack> slots, ItemStack carried, int[] dataSlots) {
            int stateId = container.incrementStateId();
            client.receiveInitialState(stateId, slots, carried);
        }

        @Override
        public void sendSlotChange(net.minecraft.world.inventory.AbstractContainerMenu container, int slot,
                                   ItemStack stack) {
            int stateId = container.incrementStateId();
            client.receiveSlot(stateId, slot, stack);
        }

        @Override
        public void sendCarriedChange(net.minecraft.world.inventory.AbstractContainerMenu container, ItemStack carried) {
            client.receiveCarried(carried);
        }

        @Override
        public void sendDataChange(net.minecraft.world.inventory.AbstractContainerMenu container, int id, int value) {
            // Card compilation predicates do not consume container data slots.
        }

        @Override
        public RemoteSlot createSlot() {
            return new MirroredRemoteSlot();
        }
    }

    private static final class MirroredRemoteSlot implements RemoteSlot {
        private ItemStack remote = ItemStack.EMPTY;

        @Override
        public void force(ItemStack stack) {
            remote = stack.copy();
        }

        @Override
        public void receive(net.minecraft.network.HashedStack incoming) {
            remote = ItemStack.EMPTY;
        }

        @Override
        public boolean matches(ItemStack stack) {
            return ItemStack.matches(remote, stack);
        }
    }

    private static void assertTrue(boolean condition) {
        assertTrue(condition, "Expected condition to be true.");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean condition) {
        assertFalse(condition, "Expected condition to be false.");
    }

    private static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    private static void assertEquals(Object expected, Object actual) {
        assertEquals(expected, actual, "Expected values to be equal.");
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + " Expected <" + expected + "> but was <" + actual + ">.");
        }
    }

    private static void assertNotNull(Object value, String message) {
        if (value == null) {
            throw new AssertionError(message);
        }
    }
}
