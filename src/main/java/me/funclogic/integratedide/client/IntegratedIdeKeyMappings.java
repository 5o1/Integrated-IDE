package me.funclogic.integratedide.client;

import com.mojang.blaze3d.platform.InputConstants;
import me.funclogic.integratedide.IntegratedIde;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

/** Client key mappings shown under the Integrated IDE controls category. */
public final class IntegratedIdeKeyMappings {
    public static final String COMPILE_KEY = "key.integratedide.compile";
    public static final String COMPLETION_KEY = "key.integratedide.completion";
    private static final KeyMapping.Category CATEGORY = new KeyMapping.Category(
            Identifier.fromNamespaceAndPath(IntegratedIde.MOD_ID, "integrated_ide"));
    public static final KeyMapping COMPILE_NOVEL = new KeyMapping(COMPILE_KEY, KeyConflictContext.GUI,
            KeyModifier.CONTROL, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_ENTER, CATEGORY);
    public static final KeyMapping REQUEST_COMPLETION = new KeyMapping(COMPLETION_KEY, KeyConflictContext.GUI,
            KeyModifier.CONTROL, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_SPACE, CATEGORY);

    private IntegratedIdeKeyMappings() {
    }

    public static void register(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(COMPILE_NOVEL);
        event.register(REQUEST_COMPLETION);
    }

    static boolean matchesCompile(KeyEvent event) {
        return COMPILE_NOVEL.matches(event);
    }

    static boolean matchesCompletion(KeyEvent event) {
        return REQUEST_COMPLETION.matches(event);
    }
}
