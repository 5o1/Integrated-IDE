package me.funclogic.integratedide.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import net.neoforged.neoforge.client.extensions.IKeyMappingExtension;
import net.neoforged.neoforge.client.settings.KeyModifier;
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

    @Test
    void honorsTheCurrentControlsScreenBindingInsteadOfHardcodingControl() {
        IKeyMappingExtension binding = (IKeyMappingExtension) (Object) IntegratedIdeKeyMappings.COMPILE_NOVEL;
        InputConstants.Key originalKey = binding.getKey();
        KeyModifier originalModifier = binding.getKeyModifier();
        try {
            binding.setKeyModifierAndCode(KeyModifier.NONE,
                    InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_K));
            assertTrue(IntegratedIdeKeyMappings.matchesCompile(new KeyEvent(GLFW.GLFW_KEY_K, 0, 0)));
            assertFalse(IntegratedIdeKeyMappings.matchesCompile(
                    new KeyEvent(GLFW.GLFW_KEY_K, 0, GLFW.GLFW_MOD_CONTROL)));
        } finally {
            binding.setKeyModifierAndCode(originalModifier, originalKey);
        }
    }
}
