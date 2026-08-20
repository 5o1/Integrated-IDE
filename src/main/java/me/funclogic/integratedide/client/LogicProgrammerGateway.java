package me.funclogic.integratedide.client;

import me.funclogic.integratedide.expr.ExpressionCompiler;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import org.cyclops.cyclopscore.network.PacketBase;
import org.cyclops.integrateddynamics.IntegratedDynamics;
import org.cyclops.integrateddynamics.api.evaluate.operator.IOperator;
import org.cyclops.integrateddynamics.api.evaluate.variable.IValueType;
import org.cyclops.integrateddynamics.api.evaluate.variable.ValueDeseralizationContext;
import org.cyclops.integrateddynamics.core.evaluate.operator.Operators;
import org.cyclops.integrateddynamics.core.evaluate.variable.ValueTypes;
import org.cyclops.integrateddynamics.core.logicprogrammer.LogicProgrammerElementTypes;
import org.cyclops.integrateddynamics.core.logicprogrammer.OperatorLPElement;
import org.cyclops.integrateddynamics.inventory.container.ContainerLogicProgrammerBase;
import org.cyclops.integrateddynamics.network.packet.LogicProgrammerActivateElementPacket;
import org.cyclops.integrateddynamics.network.packet.LogicProgrammerValueTypeBooleanValueChangedPacket;
import org.cyclops.integrateddynamics.network.packet.LogicProgrammerValueTypeIngredientsValueChangedPacket;
import org.cyclops.integrateddynamics.network.packet.LogicProgrammerValueTypeSlottedValueChangedPacket;
import org.cyclops.integrateddynamics.network.packet.LogicProgrammerValueTypeStringValueChangedPacket;

/** The only class allowed to translate a plan step into Integrated Dynamics GUI packets. */
final class LogicProgrammerGateway implements LogicProgrammerPlanSink {
    private final ContainerLogicProgrammerBase menu;

    LogicProgrammerGateway(ContainerLogicProgrammerBase menu) {
        this.menu = menu;
    }

    ContainerLogicProgrammerBase menu() {
        return menu;
    }

    boolean isCurrentMenu(Player player) {
        return player.containerMenu == menu;
    }

    int slotCount() {
        return menu.slots.size();
    }

    ItemStack slotItem(int slot) {
        return menu.slots.get(slot).getItem();
    }

    boolean slotIsEmpty(int slot) {
        return slotItem(slot).isEmpty();
    }

    ItemStack carriedItem() {
        return menu.getCarried();
    }

    void select(ExpressionCompiler.CardStep step) {
        LogicProgrammerPlanDispatcher.select(step, this);
    }

    void configure(ExpressionCompiler.CardStep step) {
        LogicProgrammerPlanDispatcher.configure(step, this);
    }

    @Override
    public void selectOperator(String operatorId) {
        IOperator operator = resolveOperator(operatorId);
        OperatorLPElement element = new OperatorLPElement(operator);
        activate(LogicProgrammerElementTypes.OPERATOR.getUniqueName(), LogicProgrammerElementTypes.OPERATOR.getName(element));
    }

    @Override
    public void selectValueType(String valueTypeId) {
        activateValueType(resolveValueType(valueTypeId));
    }

    @Override
    public void configureLiteral(ExpressionCompiler.CardStep step) {
        switch (step.kind()) {
            case STATIC_TEXT, STATIC_MOD -> send(new LogicProgrammerValueTypeStringValueChangedPacket(step.value()));
            case STATIC_BOOLEAN -> send(new LogicProgrammerValueTypeBooleanValueChangedPacket(Boolean.parseBoolean(step.value())));
            case STATIC_ITEM -> send(new LogicProgrammerValueTypeSlottedValueChangedPacket(CardLiteralFactory.itemStack(step.value())));
            case STATIC_FLUID -> send(new LogicProgrammerValueTypeSlottedValueChangedPacket(CardLiteralFactory.fluidBucket(step.value())));
            case STATIC_TAG -> send(new LogicProgrammerValueTypeIngredientsValueChangedPacket(ValueDeseralizationContext.ofClient(),
                    CardLiteralFactory.ingredientsTag(step.value())));
            case DYNAMIC_OPERATOR, EXTERNAL_REFERENCE -> throw new IllegalArgumentException(
                    "Only static literals can be configured in the Logic Programmer.");
        }
    }

    void pickup(Player player, int slot, int button) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.gameMode.handleContainerInput(menu.containerId, slot, button, ContainerInput.PICKUP, player);
    }

    void quickMove(Player player, int slot) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.QUICK_MOVE, player);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void activateValueType(IValueType<?> valueType) {
        var element = valueType.createLogicProgrammerElement();
        activate(LogicProgrammerElementTypes.VALUETYPE.getUniqueName(), LogicProgrammerElementTypes.VALUETYPE.getName(element));
    }

    private void activate(Identifier type, Identifier name) {
        menu.setActiveElementById(type, name);
        send(new LogicProgrammerActivateElementPacket(type, name));
    }

    private IOperator resolveOperator(String value) {
        Identifier id = Identifier.tryParse(value);
        IOperator operator = id == null ? null : Operators.REGISTRY.getOperator(id);
        if (operator == null) {
            throw new IllegalArgumentException("The current Logic Programmer does not register operator " + value);
        }
        return operator;
    }

    private IValueType<?> resolveValueType(String value) {
        Identifier id = Identifier.tryParse(value);
        IValueType<?> type = id == null ? null : ValueTypes.REGISTRY.getValueType(id);
        if (type == null) {
            throw new IllegalArgumentException("The current Logic Programmer does not register value type " + value);
        }
        return type;
    }

    private void send(PacketBase packet) {
        IntegratedDynamics._instance.getPacketHandler().sendToServer(packet);
    }
}
