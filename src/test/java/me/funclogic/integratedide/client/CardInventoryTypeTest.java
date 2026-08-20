package me.funclogic.integratedide.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cyclops.integrateddynamics.core.evaluate.variable.ValueTypes;
import org.junit.jupiter.api.Test;

class CardInventoryTypeTest {
    @Test
    void acceptsConcreteCardsWhereDynamicExpectsTheirCategory() {
        assertTrue(CardInventory.matchesExpectedType(ValueTypes.INTEGER, ValueTypes.CATEGORY_NUMBER));
        assertFalse(CardInventory.matchesExpectedType(ValueTypes.BOOLEAN, ValueTypes.CATEGORY_NUMBER));
    }
}
