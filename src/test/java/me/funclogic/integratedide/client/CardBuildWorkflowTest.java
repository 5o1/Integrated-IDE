package me.funclogic.integratedide.client;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import me.funclogic.integratedide.expr.ExpressionCompiler;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
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
 * driver can advance only after an explicit snapshot of that server menu.
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

        run.port.synchronizeFromServer();
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
     * one client driver pass and then exactly one explicit server-to-client
     * menu snapshot. A missing state change leaves the driver waiting.
     */
    private static void drainAfterEveryServerSnapshot(BuildRun run) {
        for (int exchanges = 0; exchanges < 256 && run.driver.isRunning(); exchanges++) {
            run.driver.tick();
            run.port.synchronizeFromServer();
        }
        assertFalse(run.driver.isRunning(), "The real Logic Programmer workflow did not reach a terminal state.");
    }

    private static void advanceUntilFirstOutputReturnIsSent(BuildRun run) {
        for (int exchanges = 0; exchanges < 128 && run.port.returnOutputRequests == 0; exchanges++) {
            run.driver.tick();
            if (run.port.returnOutputRequests == 0) {
                run.port.synchronizeFromServer();
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
     * latest delivered snapshot, never the mutable server objects themselves.
     */
    private static final class ServerDrivenProgrammer implements CardBuildPort {
        private static final Identifier EMPTY_ELEMENT_ID = Identifier.parse("");

        private final ServerLevel level;
        private final ServerPlayer player;
        private final ContainerLogicProgrammer menu;
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

        private ServerDrivenProgrammer(ServerLevel level, ServerPlayer player, ContainerLogicProgrammer menu) {
            this.level = level;
            this.player = player;
            this.menu = menu;
            this.snapshot = ObservedMenuState.capture(menu, player);
        }

        static ServerDrivenProgrammer open(MinecraftServer server, int blankCards, boolean separateStacks) {
            ServerLevel level = server.overworld();
            ServerPlayer player = FakePlayerFactory.get(level,
                    new GameProfile(UUID.randomUUID(), "integratedide_junit"));
            player.getInventory().clearContent();
            if (separateStacks) {
                for (int slot = 0; slot < blankCards; slot++) {
                    player.getInventory().setItem(slot, new ItemStack(RegistryEntries.ITEM_VARIABLE.get()));
                }
            } else {
                player.getInventory().setItem(0, new ItemStack(RegistryEntries.ITEM_VARIABLE.get(), blankCards));
            }
            ContainerLogicProgrammer menu = new ContainerLogicProgrammer(91, player.getInventory());
            player.containerMenu = menu;
            return new ServerDrivenProgrammer(level, player, menu);
        }

        void synchronizeFromServer() {
            ObservedMenuState next = ObservedMenuState.capture(menu, player);
            if (!next.sameAs(snapshot)) {
                snapshot = next;
                synchronizationRevision++;
            }
        }

        boolean serverHasReturnedCurrentOutput() {
            return menu.slots.get(LogicProgrammerMenuLayout.writeSlot(menu)).getItem().isEmpty()
                    && findPlayerStack(pendingOutput, player.getInventory().getNonEquipmentItems()) != null;
        }

        int validCardsInPlayerInventory() {
            int cards = 0;
            for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
                if (isValidVariable(stack)) {
                    cards += stack.getCount();
                }
            }
            return cards;
        }

        @Override
        public boolean isCurrent() {
            return player.containerMenu == menu;
        }

        @Override
        public long synchronizationRevision() {
            return synchronizationRevision;
        }

        @Override
        public String serverFailure() {
            if (errorBeforeAction == null || snapshot.lastError.isBlank() || snapshot.lastError.equals(errorBeforeAction)) {
                return null;
            }
            return snapshot.lastError;
        }

        @Override
        public void select(ExpressionCompiler.CardStep step) {
            beforeServerAction();
            if (step.kind() == ExpressionCompiler.StepKind.DYNAMIC_OPERATOR) {
                Identifier operatorId = Identifier.parse(step.value());
                IOperator operator = Operators.REGISTRY.getOperator(operatorId);
                assertNotNull(operator, "Dynamic did not register operator " + step.value());
                OperatorLPElement element = new OperatorLPElement(operator);
                new LogicProgrammerActivateElementPacket(LogicProgrammerElementTypes.OPERATOR.getUniqueName(),
                        LogicProgrammerElementTypes.OPERATOR.getName(element)).actionServer(level, player);
                return;
            }
            if (step.kind() == ExpressionCompiler.StepKind.EXTERNAL_REFERENCE) {
                return;
            }
            Identifier typeId = Identifier.parse(step.outputTypeId());
            IValueType<?> valueType = ValueTypes.REGISTRY.getValueType(typeId);
            assertNotNull(valueType, "Dynamic did not register value type " + step.outputTypeId());
            var element = valueType.createLogicProgrammerElement();
            new LogicProgrammerActivateElementPacket(LogicProgrammerElementTypes.VALUETYPE.getUniqueName(),
                    LogicProgrammerElementTypes.VALUETYPE.getName(element)).actionServer(level, player);
        }

        @Override
        public void configure(ExpressionCompiler.CardStep step) {
            beforeServerAction();
            switch (step.kind()) {
                case STATIC_TEXT, STATIC_MOD -> new LogicProgrammerValueTypeStringValueChangedPacket(step.value())
                        .actionServer(level, player);
                case STATIC_BOOLEAN -> new LogicProgrammerValueTypeBooleanValueChangedPacket(Boolean.parseBoolean(step.value()))
                        .actionServer(level, player);
                case STATIC_ITEM -> new LogicProgrammerValueTypeSlottedValueChangedPacket(CardLiteralFactory.itemStack(step.value()))
                        .actionServer(level, player);
                case STATIC_FLUID -> new LogicProgrammerValueTypeSlottedValueChangedPacket(CardLiteralFactory.fluidBucket(step.value()))
                        .actionServer(level, player);
                case STATIC_TAG -> new LogicProgrammerValueTypeIngredientsValueChangedPacket(ValueDeseralizationContext.of(level),
                        CardLiteralFactory.ingredientsTag(step.value())).actionServer(level, player);
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
                throw new IllegalStateException("The server inventory no longer contains input " + stepId);
            }
            pendingInput = input.copy();
            beforeServerAction();
            menu.clicked(sourceSlot, 0, ContainerInput.PICKUP, player);
        }

        @Override
        public boolean inputHeld(String stepId) {
            ItemStack expected = produced.get(stepId);
            return expected != null && sameStack(snapshot.carried, expected);
        }

        @Override
        public boolean inputSlotReady(int inputIndex) {
            return inputIndex >= 0 && inputIndex < LogicProgrammerMenuLayout.inputSlotCount(menu);
        }

        @Override
        public void placeInput(int inputIndex) {
            int target = LogicProgrammerMenuLayout.inputSlot(menu, inputIndex);
            placedInputs.put(inputIndex, pendingInput.copy());
            beforeServerAction();
            menu.clicked(target, 0, ContainerInput.PICKUP, player);
        }

        @Override
        public boolean inputPlaced(int inputIndex, String stepId) {
            ItemStack expected = placedInputs.get(inputIndex);
            if (expected == null || !inputSlotReady(inputIndex)) {
                return false;
            }
            int target = LogicProgrammerMenuLayout.inputSlot(menu, inputIndex);
            return snapshot.carried.isEmpty() && sameStack(snapshot.slot(target), expected);
        }

        @Override
        public void pickupBlank() {
            blankSourceSlot = findBlankSlot();
            if (blankSourceSlot < 0) {
                throw new IllegalStateException("Blank Variable Cards are exhausted.");
            }
            beforeServerAction();
            menu.clicked(blankSourceSlot, 0, ContainerInput.PICKUP, player);
        }

        @Override
        public boolean blankHeld() {
            return isBlankVariable(snapshot.carried);
        }

        @Override
        public void placeBlank() {
            beforeServerAction();
            menu.clicked(LogicProgrammerMenuLayout.writeSlot(menu), 1, ContainerInput.PICKUP, player);
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
            menu.clicked(blankSourceSlot, 0, ContainerInput.PICKUP, player);
            blankSourceSlot = -1;
            return true;
        }

        @Override
        public boolean blankRemainderReturned() {
            return snapshot.carried.isEmpty();
        }

        @Override
        public boolean outputReady() {
            ItemStack output = snapshot.slot(LogicProgrammerMenuLayout.writeSlot(menu));
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
            new LogicProgrammerActivateElementPacket(EMPTY_ELEMENT_ID, EMPTY_ELEMENT_ID).actionServer(level, player);
        }

        @Override
        public boolean outputReturned() {
            return pendingOutput != null && !pendingOutput.isEmpty()
                    && snapshot.slot(LogicProgrammerMenuLayout.writeSlot(menu)).isEmpty()
                    && findPlayerStack(pendingOutput, snapshot.playerInventory) != null;
        }

        @Override
        public void confirmOutput(String stepId) {
            ItemStack stored = findPlayerStack(pendingOutput, snapshot.playerInventory);
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
            int slot = LogicProgrammerMenuLayout.inputSlot(menu, inputIndex);
            if (!menu.slots.get(slot).getItem().isEmpty()) {
                beforeServerAction();
                menu.clicked(slot, 0, ContainerInput.QUICK_MOVE, player);
            }
        }

        @Override
        public boolean inputReturned(int inputIndex, String stepId) {
            ItemStack expected = placedInputs.get(inputIndex);
            if (expected == null || !inputSlotReady(inputIndex)) {
                return false;
            }
            int inputSlot = LogicProgrammerMenuLayout.inputSlot(menu, inputIndex);
            return snapshot.slot(inputSlot).isEmpty() && findPlayerStack(expected, snapshot.playerInventory) != null;
        }

        @Override
        public Map<String, ItemStack> producedCards() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(produced));
        }

        private void beforeServerAction() {
            errorBeforeAction = snapshot.lastError;
        }

        private int findBlankSlot() {
            for (int index = 0; index < menu.slots.size(); index++) {
                Slot slot = menu.slots.get(index);
                if (slot.container == player.getInventory() && isBlankVariable(slot.getItem())) {
                    return index;
                }
            }
            return -1;
        }

        private int findPlayerSlot(ItemStack expected) {
            for (int index = 0; index < menu.slots.size(); index++) {
                Slot slot = menu.slots.get(index);
                if (slot.container == player.getInventory() && sameStack(slot.getItem(), expected)) {
                    return index;
                }
            }
            return -1;
        }

        private ItemStack findPlayerStack(ItemStack expected, List<ItemStack> inventory) {
            for (ItemStack stack : inventory) {
                if (sameStack(stack, expected)) {
                    return stack;
                }
            }
            return null;
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
        private final List<ItemStack> playerInventory;
        private final String lastError;

        private ObservedMenuState(List<ItemStack> slots, ItemStack carried, List<ItemStack> playerInventory,
                                  String lastError) {
            this.slots = slots;
            this.carried = carried;
            this.playerInventory = playerInventory;
            this.lastError = lastError;
        }

        static ObservedMenuState capture(ContainerLogicProgrammerBase menu, ServerPlayer player) {
            List<ItemStack> slots = menu.slots.stream().map(slot -> slot.getItem().copy()).toList();
            List<ItemStack> inventory = player.getInventory().getNonEquipmentItems().stream().map(ItemStack::copy).toList();
            String error = menu.getLastError() == null ? "" : menu.getLastError().getString();
            return new ObservedMenuState(slots, menu.getCarried().copy(), inventory, error);
        }

        ItemStack slot(int index) {
            return index >= 0 && index < slots.size() ? slots.get(index) : ItemStack.EMPTY;
        }

        boolean sameAs(ObservedMenuState other) {
            return other != null && sameStackLists(slots, other.slots) && sameStack(carried, other.carried)
                    && sameStackLists(playerInventory, other.playerInventory) && lastError.equals(other.lastError);
        }
    }

    private static boolean sameStackLists(List<ItemStack> left, List<ItemStack> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (!sameStack(left.get(index), right.get(index))) {
                return false;
            }
        }
        return true;
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
