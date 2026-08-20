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
        materializesEveryCardForTheReportedExpressionThroughTheRealProgrammer(server);
        materializesAllThreeCardsForAnyConstantUsingActualPacketsAndInventoryClicks(server);
        materializesSeparateOneCardStacksWithoutWaitingForANonexistentRemainderSync(server);
        reusesVirtualVariablesWithoutCreatingASecondSharedIntermediateCard(server);
        remainsWaitingUntilTheActualServerResultIsDeliveredAsANewSnapshot(server);
        stopsAfterTheRealProgrammerRejectsTheThirdBlankCard(server);
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

    private static void stopsAfterTheRealProgrammerRejectsTheThirdBlankCard(MinecraftServer server) {
        BuildRun run = start(server, "anyConstant(1, 1)", 2);

        drainAfterEveryServerSnapshot(run);

        assertTrue(run.driver.isFailed());
        assertEquals(List.of("v0", "v1"), run.port.confirmedStepIds,
                "The server must retain the two already-created cards and reject only the absent third blank card.");
    }

    private static BuildRun start(MinecraftServer server, String source, int blankCards) {
        ExpressionCompiler.Compilation compilation = LogicProgrammerCatalog.create().compile(source);
        assertTrue(compilation.valid(), compilation.message());
        ServerDrivenProgrammer port = ServerDrivenProgrammer.open(server, blankCards, false);
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

    /**
     * A test safety guard, not a time-based protocol. Each iteration performs
     * one client driver pass and then exactly one explicit container-delta
     * flush. A missing state change leaves the driver waiting.
     */
    private static void drainAfterEveryServerSnapshot(BuildRun run) {
        for (int exchanges = 0; exchanges < 256 && run.driver.isRunning(); exchanges++) {
            run.driver.tick();
            run.port.flushServerChanges();
        }
        assertFalse(run.driver.isRunning(), "The real Logic Programmer workflow did not reach a terminal state.");
    }

    private static void advanceUntilFirstOutputReturnIsSent(BuildRun run) {
        for (int exchanges = 0; exchanges < 128 && run.port.returnOutputRequests == 0; exchanges++) {
            run.driver.tick();
            if (run.port.returnOutputRequests == 0) {
                run.port.flushServerChanges();
            }
        }
        assertEquals(1, run.port.returnOutputRequests,
                "The test did not reach the first real reset-packet transition.");
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
        private final Map<String, ItemStack> produced = new LinkedHashMap<>();
        private final Map<Integer, ItemStack> placedInputs = new LinkedHashMap<>();
        private final List<String> confirmedStepIds = new ArrayList<>();
        private ObservedMenuState snapshot;
        private long synchronizationRevision;
        private ItemStack pendingInput = ItemStack.EMPTY;
        private ItemStack pendingOutput = ItemStack.EMPTY;
        private int blankSourceSlot = -1;
        private String errorBeforeAction;
        private int returnOutputRequests;

        private ServerDrivenProgrammer(ServerLevel level, ServerPlayer serverPlayer, ServerPlayer clientPlayer,
                                       ContainerLogicProgrammer serverMenu, ContainerLogicProgrammer clientMenu) {
            this.level = level;
            this.serverPlayer = serverPlayer;
            this.clientPlayer = clientPlayer;
            this.serverMenu = serverMenu;
            this.clientMenu = clientMenu;
            this.snapshot = ObservedMenuState.empty(clientMenu.slots.size());
            // This is the server's actual container-delta boundary. The test
            // driver can see a slot only after AbstractContainerMenu has
            // emitted it through this synchronizer, never by inspecting the
            // mutable server menu directly.
            this.serverMenu.setSynchronizer(new LoopbackMenuSynchronizer(this));
        }

        static ServerDrivenProgrammer open(MinecraftServer server, int blankCards, boolean separateStacks) {
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
            return new ServerDrivenProgrammer(level, serverPlayer, clientPlayer, serverMenu, clientMenu);
        }

        void flushServerChanges() {
            serverMenu.broadcastChanges();
        }

        private void receiveInitialState(int stateId, List<ItemStack> slots, ItemStack carried) {
            clientMenu.initializeContents(stateId, slots, carried);
            snapshot = ObservedMenuState.capture(clientMenu);
            synchronizationRevision++;
        }

        private void receiveSlot(int stateId, int slot, ItemStack stack) {
            clientMenu.setItem(slot, stateId, stack);
            snapshot = ObservedMenuState.capture(clientMenu);
            synchronizationRevision++;
        }

        private void receiveCarried(ItemStack carried) {
            clientMenu.setCarried(carried);
            snapshot = ObservedMenuState.capture(clientMenu);
            synchronizationRevision++;
        }

        boolean serverHasReturnedCurrentOutput() {
            return serverMenu.slots.get(LogicProgrammerMenuLayout.writeSlot(serverMenu)).getItem().isEmpty()
                    && findServerPlayerStack(pendingOutput) != null;
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
            beforeServerAction();
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
            clientMenu.setActiveElementById(elementType, elementId);
            new LogicProgrammerActivateElementPacket(elementType, elementId).actionServer(level, serverPlayer);
        }

        @Override
        public void configure(ExpressionCompiler.CardStep step) {
            beforeServerAction();
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
            int sourceSlot = findPlayerSlot(input);
            if (sourceSlot < 0) {
                throw new IllegalStateException("The synchronized inventory no longer contains input " + stepId);
            }
            pendingInput = input.copy();
            beforeServerAction();
            serverMenu.clicked(sourceSlot, 0, ContainerInput.PICKUP, serverPlayer);
        }

        @Override
        public boolean inputHeld(String stepId) {
            ItemStack expected = produced.get(stepId);
            return expected != null && sameStack(snapshot.carried, expected);
        }

        @Override
        public boolean inputSlotReady(int inputIndex) {
            return inputIndex >= 0 && inputIndex < LogicProgrammerMenuLayout.inputSlotCount(clientMenu);
        }

        @Override
        public void placeInput(int inputIndex) {
            int target = LogicProgrammerMenuLayout.inputSlot(clientMenu, inputIndex);
            placedInputs.put(inputIndex, pendingInput.copy());
            beforeServerAction();
            serverMenu.clicked(target, 0, ContainerInput.PICKUP, serverPlayer);
        }

        @Override
        public boolean inputPlaced(int inputIndex, String stepId) {
            ItemStack expected = placedInputs.get(inputIndex);
            if (expected == null || !inputSlotReady(inputIndex)) {
                return false;
            }
            int target = LogicProgrammerMenuLayout.inputSlot(clientMenu, inputIndex);
            return snapshot.carried.isEmpty() && sameStack(snapshot.slot(target), expected);
        }

        @Override
        public void pickupBlank() {
            blankSourceSlot = findBlankSlot();
            if (blankSourceSlot < 0) {
                throw new IllegalStateException("Blank Variable Cards are exhausted.");
            }
            beforeServerAction();
            serverMenu.clicked(blankSourceSlot, 0, ContainerInput.PICKUP, serverPlayer);
        }

        @Override
        public boolean blankHeld() {
            return isBlankVariable(snapshot.carried);
        }

        @Override
        public void placeBlank() {
            beforeServerAction();
            serverMenu.clicked(LogicProgrammerMenuLayout.writeSlot(clientMenu), 1, ContainerInput.PICKUP, serverPlayer);
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
            beforeServerAction();
            serverMenu.clicked(blankSourceSlot, 0, ContainerInput.PICKUP, serverPlayer);
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
            pendingOutput = output.copy();
            return true;
        }

        @Override
        public void returnOutput() {
            beforeServerAction();
            returnOutputRequests++;
            new LogicProgrammerActivateElementPacket(EMPTY_ELEMENT_ID, EMPTY_ELEMENT_ID).actionServer(level, serverPlayer);
        }

        @Override
        public boolean outputReturned() {
            return pendingOutput != null && !pendingOutput.isEmpty()
                    && snapshot.slot(LogicProgrammerMenuLayout.writeSlot(clientMenu)).isEmpty()
                    && findReceivedPlayerStack(pendingOutput) != null;
        }

        @Override
        public void confirmOutput(String stepId) {
            ItemStack stored = findReceivedPlayerStack(pendingOutput);
            if (stored == null) {
                throw new IllegalStateException("The returned output was absent from the synchronized inventory.");
            }
            produced.put(stepId, stored.copy());
            confirmedStepIds.add(stepId);
            pendingOutput = ItemStack.EMPTY;
        }

        @Override
        public void cleanupInput(int inputIndex) {
            if (!inputSlotReady(inputIndex)) {
                throw new IllegalStateException("The active element no longer exposes input " + inputIndex + '.');
            }
            int slot = LogicProgrammerMenuLayout.inputSlot(clientMenu, inputIndex);
            if (!snapshot.slot(slot).isEmpty()) {
                beforeServerAction();
                serverMenu.clicked(slot, 0, ContainerInput.QUICK_MOVE, serverPlayer);
            }
        }

        @Override
        public boolean inputReturned(int inputIndex, String stepId) {
            ItemStack expected = placedInputs.get(inputIndex);
            if (expected == null || !inputSlotReady(inputIndex)) {
                return false;
            }
            int inputSlot = LogicProgrammerMenuLayout.inputSlot(clientMenu, inputIndex);
            return snapshot.slot(inputSlot).isEmpty() && findReceivedPlayerStack(expected) != null;
        }

        @Override
        public Map<String, ItemStack> producedCards() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(produced));
        }

        private void beforeServerAction() {
            errorBeforeAction = errorText();
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

        private int findPlayerSlot(ItemStack expected) {
            for (int index = 0; index < clientMenu.slots.size(); index++) {
                Slot slot = clientMenu.slots.get(index);
                if (slot.container == clientPlayer.getInventory() && sameStack(slot.getItem(), expected)) {
                    return index;
                }
            }
            return -1;
        }

        private ItemStack findReceivedPlayerStack(ItemStack expected) {
            for (int slot : playerInventorySlots(clientMenu, clientPlayer)) {
                ItemStack stack = snapshot.slot(slot);
                if (sameStack(stack, expected)) {
                    return stack;
                }
            }
            return null;
        }

        private ItemStack findServerPlayerStack(ItemStack expected) {
            for (ItemStack stack : serverPlayer.getInventory().getNonEquipmentItems()) {
                if (sameStack(stack, expected)) {
                    return stack;
                }
            }
            return null;
        }

        private String errorText() {
            return serverMenu.getLastError() == null ? "" : serverMenu.getLastError().getString();
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

    private static boolean sameStack(ItemStack left, ItemStack right) {
        return left.getCount() == right.getCount() && ItemStack.isSameItemSameComponents(left, right);
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
