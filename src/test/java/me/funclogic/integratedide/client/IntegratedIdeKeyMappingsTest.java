package me.funclogic.integratedide.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.client.input.KeyEvent;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

class IntegratedIdeKeyMappingsTest {
    @Test
    void compilationRequiresControlAndEnter() {
        assertFalse(IntegratedIdeKeyMappings.matchesCompile(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0)));
        assertTrue(IntegratedIdeKeyMappings.matchesCompile(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, GLFW.GLFW_MOD_CONTROL)));
    }

    @Test
    void completionRequiresControlAndSpace() {
        assertFalse(IntegratedIdeKeyMappings.matchesCompletion(new KeyEvent(GLFW.GLFW_KEY_SPACE, 0, 0)));
        assertTrue(IntegratedIdeKeyMappings.matchesCompletion(new KeyEvent(GLFW.GLFW_KEY_SPACE, 0, GLFW.GLFW_MOD_CONTROL)));
    }
}
